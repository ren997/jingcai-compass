package com.jingcaicompass.backtest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.backtest.entity.OddsHistoryArchiveEntity;
import com.jingcaicompass.backtest.enums.BacktestArchiveStatusEnum;
import com.jingcaicompass.backtest.mapper.OddsHistoryArchiveMapper;
import com.jingcaicompass.odds.client.NovaApiHistoryClient;
import com.jingcaicompass.odds.service.OddsHistoryPayloadTrimmer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 历史赔率归档：把「这场的历史序列」变成一条可复用的外部事实。
 *
 * <p><b>先查后采</b>而非先采后去重：归档是只追加表，一次误采会永久留下一条记录；
 * 而复用判定只要一次索引查询。所以顺序是「已有终局结论 → 直接复用（0 次调用）」，
 * 只有确实没有时才发请求。</p>
 *
 * <p><b>「终局」与「失败」必须分开</b>（见 {@link BacktestArchiveStatusEnum}）：
 * {@code NO_HISTORY} / {@code NO_QUOTE_YET} 是终局，会被复用为负缓存；{@code RETRYABLE_FAILURE}
 * 下次必须重采。把两者都当「失败」会让回溯窗口边界（实测约 4 个月）反复吃满额度，
 * 把两者都当「终局」则会让偶发的上游 500 变成永久的「这场没数据」。</p>
 *
 * <p><b>本类不写任何业务结论表</b>：归档只描述「上游给了什么」，
 * {@code backtest_cases} 由判定阶段写、{@code settlements} 永不写。这条边界是
 * 「回测结论不得流进线上统计口径」的第一道保证。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BacktestArchiveService {

    private static final Logger log = LoggerFactory.getLogger(BacktestArchiveService.class);

    private final OddsHistoryArchiveMapper archiveMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<NovaApiHistoryClient> historyClientProvider;

    /** 纯函数，无需容器托管：无状态、无配置依赖，直接持有实例比多一个 bean 更容易单测。 */
    private final OddsHistoryPayloadTrimmer trimmer = new OddsHistoryPayloadTrimmer();

    public BacktestArchiveService(
            OddsHistoryArchiveMapper archiveMapper,
            ObjectMapper objectMapper,
            ObjectProvider<NovaApiHistoryClient> historyClientProvider
    ) {
        this.archiveMapper = archiveMapper;
        this.objectMapper = objectMapper;
        this.historyClientProvider = historyClientProvider;
    }

    /**
     * 确保一场比赛已有可用的历史序列归档。
     *
     * @param request 场次身份 + 归档元信息
     * @return 结论；{@code reused} 为真表示没有发请求
     */
    public ArchiveOutcome ensureArchived(ArchiveRequest request) {
        OddsHistoryArchiveEntity existing = archiveMapper.selectFinalByExternalMatchId(
                request.providerCode(), request.externalMatchId());
        if (existing != null) {
            // 已终局：成功序列直接复用，负缓存也复用（重采不会有不同结果）。
            return new ArchiveOutcome(
                    existing.getFetchStatus(),
                    existing.getId(),
                    true,
                    0,
                    existing.getFailureMessage()
            );
        }

        NovaApiHistoryClient historyClient = historyClientProvider.getIfAvailable();
        if (historyClient == null) {
            // 单源下这只会发生在 provider=stub 的本地环境：没有历史数据源就不该假装在回测。
            throw new IllegalStateException(
                    "当前未装配 NovaAPI 历史赔率客户端（app.asian-odds.provider 不是 NOVA_API），"
                            + "无法归档历史序列");
        }

        NovaApiHistoryClient.HistoryFetch fetch = historyClient.fetch(request.externalMatchId());
        if (fetch.noHistory()) {
            return persistFailure(request, fetch, BacktestArchiveStatusEnum.NO_HISTORY,
                    "上游无该场历史记录（约 4 个月以外的场次通常是这个原因）");
        }
        if (!fetch.success()) {
            BacktestArchiveStatusEnum status =
                    fetch.isBodyLevelRetryable() || isTransportFailure(fetch)
                            ? BacktestArchiveStatusEnum.RETRYABLE_FAILURE
                            : BacktestArchiveStatusEnum.PERMANENT_FAILURE;
            return persistFailure(request, fetch, status, failureMessage(fetch));
        }

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(fetch.payload());
        if (!trimmed.hasSeries()) {
            return persistFailure(request, fetch, BacktestArchiveStatusEnum.NO_QUOTE_YET,
                    "响应成功但两个市场内没有可用变快点（越早的场次序列越稀疏）");
        }

        String payloadJson = serialize(trimmed.payload());
        String payloadHash = sha256(payloadJson);
        OddsHistoryArchiveEntity entity = baseEntity(request);
        entity.setFetchStatus(BacktestArchiveStatusEnum.SUCCESS);
        entity.setHttpStatus(fetch.httpStatus());
        entity.setUpstreamCode(fetch.upstreamCode());
        entity.setPayloadHash(payloadHash);
        entity.setBookmakerCount(trimmed.bookmakerCount());
        entity.setPointCount(trimmed.pointCount());
        entity.setSeriesStartAt(trimmed.seriesStartAt());
        entity.setSeriesEndAt(trimmed.seriesEndAt());
        entity.setTrimmed(trimmed.trimmed());

        int inserted = archiveMapper.insertSuccessIgnore(entity, payloadJson);
        if (inserted == 1) {
            log.info("历史赔率已归档 externalMatchId={} bookmakers={} points={} trimmed={}",
                    request.externalMatchId(), trimmed.bookmakerCount(), trimmed.pointCount(),
                    trimmed.trimmed());
            // 手写 INSERT 不会回填自增 id，重新读一次：调用方要拿 archive_id 建 case 外键，
            // 让它去猜（或再查一次）等于把这条依赖泄漏到调用方。
            OddsHistoryArchiveEntity persisted = archiveMapper.selectFinalByExternalMatchId(
                    request.providerCode(), request.externalMatchId());
            return new ArchiveOutcome(
                    BacktestArchiveStatusEnum.SUCCESS,
                    persisted == null ? null : persisted.getId(),
                    false,
                    fetch.quotaCost(),
                    null
            );
        }

        // 并发下另一个 worker 先落了同一场：委托给「复用」语义，而不是把它当成失败。
        OddsHistoryArchiveEntity raced = archiveMapper.selectFinalByExternalMatchId(
                request.providerCode(), request.externalMatchId());
        if (raced == null) {
            // 冲突却查不到行，说明唯一索引命中的是 SUCCESS 以外的结论——那不该发生，交给调用方看见。
            throw new IllegalStateException(
                    "归档写入被ON CONFLICT跳过但读不到终局行，externalMatchId=" + request.externalMatchId());
        }
        return new ArchiveOutcome(
                raced.getFetchStatus(),
                raced.getId(),
                true,
                fetch.quotaCost(),
                raced.getFailureMessage()
        );
    }

    private ArchiveOutcome persistFailure(
            ArchiveRequest request,
            NovaApiHistoryClient.HistoryFetch fetch,
            BacktestArchiveStatusEnum status,
            String message
    ) {
        OddsHistoryArchiveEntity entity = baseEntity(request);
        entity.setFetchStatus(status);
        entity.setHttpStatus(fetch.httpStatus());
        entity.setUpstreamCode(fetch.upstreamCode());
        entity.setFailureMessage(truncate(message));
        entity.setTrimmed(Boolean.FALSE);
        archiveMapper.insertFailure(entity);
        log.info("历史赔率未归档 externalMatchId={} status={} http={} code={} reason={}",
                request.externalMatchId(), status, fetch.httpStatus(), fetch.upstreamCode(), message);
        return new ArchiveOutcome(status, entity.getId(), false, fetch.quotaCost(), message);
    }

    private static boolean isTransportFailure(NovaApiHistoryClient.HistoryFetch fetch) {
        // 没有响应体也没有 HTTP 状态：网络层重试耗尽（超时/连接失败），下次值得再试。
        return fetch.payload() == null && (fetch.httpStatus() == null || fetch.httpStatus() >= 500);
    }

    private static String failureMessage(NovaApiHistoryClient.HistoryFetch fetch) {
        if (fetch.transportError() != null) {
            return "网络层失败：" + fetch.transportError();
        }
        return "HTTP %s / code %s".formatted(fetch.httpStatus(), fetch.upstreamCode());
    }

    private static OddsHistoryArchiveEntity baseEntity(ArchiveRequest request) {
        OddsHistoryArchiveEntity entity = new OddsHistoryArchiveEntity();
        entity.setProviderCode(request.providerCode());
        entity.setExternalMatchId(request.externalMatchId());
        entity.setMatchId(request.matchId());
        entity.setLotteryDate(request.lotteryDate());
        entity.setLeagueName(request.leagueName());
        entity.setHomeTeamName(request.homeTeamName());
        entity.setAwayTeamName(request.awayTeamName());
        entity.setKickoffTime(request.kickoffTime());
        entity.setAttemptedAt(Instant.now());
        return entity;
    }

    private String serialize(com.fasterxml.jackson.databind.JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("归档载荷序列化失败", exception);
        }
    }

    /**
     * 载荷摘要。
     *
     * <p>算的是<b>我们收到的这段文本</b>，而不是某种规范形式：落库是 JSONB，读回来键序已被
     * PG 重排，因此这个哈希不可能从库里重算出来。它的用途是「同一份响应不会被重复归档」与
     * 「结论能追回到某一份具体载荷」，不是内容寻址。</p>
     */
    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        // 失败原因列是 TEXT，但把它当无界日志用会让单行体积失控；保留前 1000 字符足够定位。
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /**
     * 一场比赛归档所需的全部身份信息。
     *
     * @param matchId 内部比赛 ID；null 表示赛程里还没有这场（归档仍保留，供将来回填后复用）
     */
    public record ArchiveRequest(
            String providerCode,
            String externalMatchId,
            Long matchId,
            LocalDate lotteryDate,
            String leagueName,
            String homeTeamName,
            String awayTeamName,
            Instant kickoffTime
    ) {
    }

    /**
     * 归档结论。
     *
     * @param status   补采结论
     * @param archiveId 落库行 id；理论上不会为空，除极端并发外
     * @param reused   是否直接复用了已有归档（true 表示本次没有发 API 请求）
     * @param quotaCost 本次实际消耗的额度
     */
    public record ArchiveOutcome(
            BacktestArchiveStatusEnum status,
            Long archiveId,
            boolean reused,
            int quotaCost,
            String message
    ) {

        /** 可以直接进入切片与判定阶段。 */
        public boolean usable() {
            return status == BacktestArchiveStatusEnum.SUCCESS;
        }
    }
}
