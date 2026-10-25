package com.jingcaicompass.odds.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 亚盘同步实现：ProviderSyncTemplate → 比赛解析门禁 → 追加快照。
 *
 * <p>比赛解析只有一条路径：赔率响应里的 {@code matchId} 就是赛果结算用的同一个竞彩场次 ID，
 * 直接按 {@code matches.external_match_id} 定位，<b>不经过任何映射表</b>。映射表与归一化链
 * 已在阶段 5 删除，历史上「供应商赛事 ID → 已确认映射」的回退路径不再存在。</p>
 *
 * <p>不要把这条路径写成「查不到就自动确认」——那会让场次静默错配；查不到就是
 * {@code unmapped}，计入 {@code skippedUnmapped} 并在同步记录里留痕。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class AsianOddsSyncServiceImpl implements AsianOddsSyncService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AsianOddsProvider asianOddsProvider;
    private final ProviderSyncTemplate providerSyncTemplate;
    private final AsianOddsPayloadMapper payloadMapper;
    private final AsianOddsSnapshotWriter snapshotWriter;
    private final MatchMapper matchMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final ObjectMapper objectMapper;

    public AsianOddsSyncServiceImpl(
            AsianOddsProvider asianOddsProvider,
            ProviderSyncTemplate providerSyncTemplate,
            AsianOddsPayloadMapper payloadMapper,
            AsianOddsSnapshotWriter snapshotWriter,
            MatchMapper matchMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            ObjectMapper objectMapper
    ) {
        this.asianOddsProvider = asianOddsProvider;
        this.providerSyncTemplate = providerSyncTemplate;
        this.payloadMapper = payloadMapper;
        this.snapshotWriter = snapshotWriter;
        this.matchMapper = matchMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AsianOddsSyncResultDto sync(AsianOddsSyncRequestDto request) {
        // 1) 业务日与查询时间窗
        LocalDate businessDate = request == null || request.businessDate() == null
                ? LocalDate.now(SHANGHAI)
                : request.businessDate();

        List<MatchEntity> dayMatches = matchMapper.selectList(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getLotteryDate, businessDate));
        int sportteryMatchCount = dayMatches.size();

        // 当天没有比赛时直接收尾：NovaAPI 的赔率按场次拉取，没有场次就没有可请求的对象，
        // 继续走下去只会在 Provider 里抛「没有任何比赛」，把「今天没球」记成同步失败。
        if (dayMatches.isEmpty()) {
            return emptyResult(businessDate, sportteryMatchCount, 0);
        }

        AsianOddsQueryDto query = buildQuery(businessDate, dayMatches);
        int usedQuota = 0;

        AtomicInteger snapshotInsertCount = new AtomicInteger();
        AtomicInteger skippedUnmapped = new AtomicInteger();
        AtomicInteger skippedLive = new AtomicInteger();
        AtomicInteger skippedIncomplete = new AtomicInteger();
        Map<String, MatchEntity> matchesByExternalId = indexByExternalMatchId(dayMatches);
        Instant now = Instant.now();

        // 3) 模板：拉 raw → 幂等入库 → 解析写快照
        ProviderSyncOutcome outcome = providerSyncTemplate.execute(
                asianOddsProvider.providerCode(),
                ProviderDataTypeEnum.ASIAN_ODDS,
                () -> asianOddsProvider.fetchPreMatchOddsRaw(query),
                (dataType, requestKey, payload) -> {
                    List<AsianOddsMatchOddsDto> matches = payloadMapper.parseMatches(toJson(payload.getPayload()));
                    int success = 0;
                    int failure = 0;
                    StringBuilder errors = new StringBuilder();

                    for (AsianOddsMatchOddsDto matchOdds : matches) {
                        try {
                            if (StringUtils.hasText(matchOdds.parseError())) {
                                failure++;
                                appendError(errors, matchOdds.providerMatchId(), matchOdds.parseError());
                                continue;
                            }
                            // 4) 解析比赛：按 external_match_id 直连，不经过映射表
                            MatchLookup lookup = lookupMatch(matchOdds, matchesByExternalId, now);
                            if (lookup.skipReason() == SkipReason.LIVE) {
                                skippedLive.incrementAndGet();
                                success++;
                                continue;
                            }
                            if (lookup.skipReason() == SkipReason.UNMAPPED) {
                                skippedUnmapped.incrementAndGet();
                                success++;
                                continue;
                            }

                            // 5) 盘口不完整时只计数，不写快照
                            List<AsianOddsLineDto> lines =
                                    matchOdds.lines() == null ? List.of() : matchOdds.lines();
                            if (lines.isEmpty()) {
                                skippedIncomplete.incrementAndGet();
                                success++;
                                continue;
                            }
                            AsianOddsSnapshotWriter.WriteResult writeResult = snapshotWriter.writeLines(
                                    lookup.matchId(),
                                    asianOddsProvider.providerCode(),
                                    lines,
                                    payload.getPayloadHash()
                            );
                            snapshotInsertCount.addAndGet(writeResult.snapshotInsertCount());
                            skippedIncomplete.addAndGet(writeResult.skippedIncomplete());
                            success += writeResult.parseResult().successCount();
                            failure += writeResult.parseResult().failureCount();
                            if (writeResult.parseResult().errorMessage() != null) {
                                appendError(errors, matchOdds.providerMatchId(), writeResult.parseResult().errorMessage());
                            }
                        } catch (RuntimeException exception) {
                            failure++;
                            appendError(errors, matchOdds.providerMatchId(), exception.getClass().getSimpleName());
                        }
                    }

                    String errorMessage = errors.isEmpty() ? null : truncate(errors.toString());
                    if (success == 0 && failure == 0) {
                        return ProviderParseResult.empty();
                    }
                    return new ProviderParseResult(success, failure, errorMessage);
                }
        );

        // 6) 汇总新增快照、覆盖比赛数和本次额度消耗
        int covered = countCoveredMatches(dayMatches);
        int quotaCostUsed = outcome.syncRun() == null || outcome.syncRun().getQuotaCost() == null
                ? 0
                : outcome.syncRun().getQuotaCost();

        return new AsianOddsSyncResultDto(
                outcome,
                false,
                snapshotInsertCount.get(),
                skippedUnmapped.get(),
                skippedLive.get(),
                skippedIncomplete.get(),
                sportteryMatchCount,
                covered,
                coverageRate(sportteryMatchCount, covered),
                quotaCostUsed
        );
    }

    /**
     * 当日无比赛：仍然走一次同步模板，落一条「零场次」的成功记录。
     *
     * <p>不要在这里抛异常：NovaAPI 的赔率按场次拉取，没有场次就没有可请求的对象，
     * 把它记成同步失败会让「今天没球」和「数据源坏了」在运维视图里长得一模一样。</p>
     */
    private AsianOddsSyncResultDto emptyResult(
            LocalDate businessDate,
            int sportteryMatchCount,
            int quotaCostUsed
    ) {
        ProviderFetchResult empty = new ProviderFetchResult(
                asianOddsProvider.providerCode() + "-odds-empty:" + businessDate,
                "{\"provider\":\"" + asianOddsProvider.providerCode() + "\",\"responses\":[]}",
                200,
                Instant.now(),
                0,
                0
        );
        ProviderSyncOutcome outcome = providerSyncTemplate.execute(
                asianOddsProvider.providerCode(),
                ProviderDataTypeEnum.ASIAN_ODDS,
                () -> empty,
                (dataType, requestKey, payload) -> ProviderParseResult.empty()
        );
        return new AsianOddsSyncResultDto(
                outcome,
                false,
                0,
                0,
                0,
                0,
                sportteryMatchCount,
                0,
                0,
                coverageRate(sportteryMatchCount, 0),
                quotaCostUsed
        );
    }

    private AsianOddsQueryDto buildQuery(
            LocalDate businessDate,
            List<MatchEntity> dayMatches
    ) {
        List<String> matchExternalIds = dayMatches.stream()
                .map(MatchEntity::getExternalMatchId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (dayMatches == null || dayMatches.isEmpty()) {
            OffsetDateTime from = businessDate.atStartOfDay(SHANGHAI).toOffsetDateTime();
            OffsetDateTime to = businessDate.plusDays(1).atStartOfDay(SHANGHAI).toOffsetDateTime().minusNanos(1);
            return new AsianOddsQueryDto(null, from, to, null, List.of(), matchExternalIds);
        }
        Instant minKickoff = dayMatches.stream()
                .map(MatchEntity::getKickoffTime)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(businessDate.atStartOfDay(SHANGHAI).toInstant());
        Instant maxKickoff = dayMatches.stream()
                .map(MatchEntity::getKickoffTime)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(businessDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant().minusNanos(1));
        return new AsianOddsQueryDto(
                null,
                OffsetDateTime.ofInstant(minKickoff, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(maxKickoff, ZoneOffset.UTC),
                null,
                List.of(),
                matchExternalIds
        );
    }

    /** 单源下 {@code external_match_id} 是权威键，提前建索引避免逐场回表。 */
    private static Map<String, MatchEntity> indexByExternalMatchId(List<MatchEntity> dayMatches) {
        Map<String, MatchEntity> index = new HashMap<>();
        for (MatchEntity match : dayMatches) {
            if (match == null || !StringUtils.hasText(match.getExternalMatchId())) {
                continue;
            }
            index.putIfAbsent(match.getExternalMatchId().trim(), match);
        }
        return index;
    }

    /**
     * 定位这场赔率对应的比赛。
     *
     * <p>只按 {@code external_match_id} 直连，并多一道<b>开赛判定</b>：{@code /nova/tf/odds}
     * 不返回「是否滚球」标志，而本表写入的是赛前快照，冻结行按它计账。开赛后仍拿到的赔率若照写，
     * 模型会把「已经在踢的盘」当成赛前共识，这是靠任何下游校验都发现不了的污染，只能在这里拦。</p>
     */
    private MatchLookup lookupMatch(
            AsianOddsMatchOddsDto matchOdds,
            Map<String, MatchEntity> matchesByExternalId,
            Instant now
    ) {
        String externalMatchId = matchOdds.providerMatchId();
        MatchEntity match = StringUtils.hasText(externalMatchId)
                ? matchesByExternalId.get(externalMatchId.trim())
                : null;
        if (match == null) {
            return MatchLookup.unmapped();
        }
        if (match.getKickoffTime() != null && !match.getKickoffTime().isAfter(now)) {
            return MatchLookup.live();
        }
        return MatchLookup.resolved(match.getId());
    }

    /** 覆盖数必须按当前来源统计，否则历史 STUB 行会让覆盖率虚高到 100%。 */
    private int countCoveredMatches(List<MatchEntity> dayMatches) {
        if (dayMatches == null || dayMatches.isEmpty()) {
            return 0;
        }
        String providerCode = asianOddsProvider.providerCode();
        Set<Long> matchIds = new HashSet<>();
        for (MatchEntity match : dayMatches) {
            if (match.getId() == null) {
                continue;
            }
            Long count = asianOddsSnapshotMapper.selectCount(new LambdaQueryWrapper<AsianOddsSnapshot>()
                    .eq(AsianOddsSnapshot::getMatchId, match.getId())
                    .eq(AsianOddsSnapshot::getProviderCode, providerCode));
            if (count != null && count > 0) {
                matchIds.add(match.getId());
            }
        }
        return matchIds.size();
    }

    private static BigDecimal coverageRate(int sportteryMatchCount, int coveredMatchCount) {
        if (sportteryMatchCount <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(coveredMatchCount)
                .divide(BigDecimal.valueOf(sportteryMatchCount), 4, RoundingMode.HALF_UP);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_PARSE_FAILED, "无法序列化亚盘原始载荷", exception);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static void appendError(StringBuilder target, String matchId, String reason) {
        if (!target.isEmpty()) {
            target.append("; ");
        }
        String safeMatchId = StringUtils.hasText(matchId) ? matchId : "unknown";
        String safeReason = StringUtils.hasText(reason) ? reason : "PARSE_FAILURE";
        target.append(safeMatchId).append(':').append(safeReason);
    }

    /** 比赛解析结果：命中 id、或因为哪条原因跳过。 */
    private record MatchLookup(Long matchId, SkipReason skipReason) {

        private static MatchLookup resolved(Long matchId) {
            return new MatchLookup(matchId, null);
        }

        private static MatchLookup unmapped() {
            return new MatchLookup(null, SkipReason.UNMAPPED);
        }

        private static MatchLookup live() {
            return new MatchLookup(null, SkipReason.LIVE);
        }
    }

    private enum SkipReason {
        UNMAPPED,
        LIVE
    }
}
