package com.jingcaicompass.match.client;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.match.service.NovaMatchResultPayloadMapper;
import com.jingcaicompass.match.service.NovaSchedulePayloadMapper;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * NovaAPI 竞彩数据源。赛程、赔率、赛果共用同一个 {@code matchId}，是单源化的基础。
 *
 * <p>赛程走 {@code /nova/sports-lottery/all-list?date=}，赛果走
 * {@code /nova/text/match-results?startDate=&endDate=}。两者都以 {@code matchId} 为主键，
 * 因此不需要任何映射层。</p>
 */
@Component
@ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "nova")
public class NovaSportteryProvider implements SportteryProvider {

    public static final String PROVIDER_CODE = "NOVA_API";

    static final String SCHEDULE_PATH = "/nova/sports-lottery/all-list";
    static final String MATCH_RESULTS_PATH = "/nova/text/match-results";

    /** 赛果接口单次跨度上限（官方限制），超过会被服务端拒绝。 */
    static final int MAX_RESULT_QUERY_SPAN_DAYS = 30;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final NovaApiClient client;
    private final NovaSchedulePayloadMapper schedulePayloadMapper;
    private final NovaMatchResultPayloadMapper matchResultPayloadMapper;

    public NovaSportteryProvider(
            NovaApiClient client,
            NovaSchedulePayloadMapper schedulePayloadMapper,
            NovaMatchResultPayloadMapper matchResultPayloadMapper
    ) {
        this.client = client;
        this.schedulePayloadMapper = schedulePayloadMapper;
        this.matchResultPayloadMapper = matchResultPayloadMapper;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate) {
        LocalDate requestDate = lotteryDate == null ? LocalDate.now(SHANGHAI) : lotteryDate;
        return parseMatchPool(fetchMatchPoolRaw(requestDate).payloadJson(), requestDate).stream()
                .map(item -> new SportteryMatchDto(
                        item.externalMatchId(),
                        item.lotteryDate(),
                        item.lotteryMatchNo(),
                        item.leagueName(),
                        item.homeTeamName(),
                        item.awayTeamName(),
                        item.kickoffTime(),
                        item.officialHandicap() == null ? null : item.officialHandicap().intValue(),
                        item.matchStatus()
                ))
                .toList();
    }

    @Override
    public ProviderFetchResult fetchMatchPoolRaw(LocalDate lotteryDate) {
        LocalDate requestDate = lotteryDate == null ? LocalDate.now(SHANGHAI) : lotteryDate;
        ProviderHttpResponse response = call(SCHEDULE_PATH, Map.of("date", requestDate.toString()));
        return new ProviderFetchResult(
                requestDate.toString(),
                response.body(),
                response.status(),
                Instant.now(),
                response.retryCount(),
                response.quotaCost()
        );
    }

    @Override
    public List<SportteryPoolSyncItemDto> parseMatchPool(String rawJson, LocalDate businessDate) {
        if (!StringUtils.hasText(rawJson)) {
            throw new SportteryDataAccessException("NovaAPI 赛程接口返回空响应");
        }
        return schedulePayloadMapper.parseItems(rawJson, businessDate);
    }

    /**
     * 按竞彩业务日区间拉取官方赛果。
     *
     * <p>接口限制单次跨度 ≤ 30 天，超出直接拒绝而不是让服务端返回 400：
     * 定时任务的补数窗口由 {@code app.tasks.match-result.lookback-days} 控制（默认 7 天），
     * 一旦有人把窗口调大到 30 天以上，这里会立刻给出可读的配置错误。</p>
     */
    @Override
    public ProviderFetchResult fetchMatchResultsRaw(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (spanDays > MAX_RESULT_QUERY_SPAN_DAYS) {
            throw new IllegalArgumentException(
                    "NovaAPI 赛果单次查询跨度不得超过 %d 天，实际 %d 天：%s~%s".formatted(
                            MAX_RESULT_QUERY_SPAN_DAYS, spanDays, startDate, endDate
                    )
            );
        }
        ProviderHttpResponse response = call(MATCH_RESULTS_PATH, Map.of(
                "startDate", startDate.toString(),
                "endDate", endDate.toString()
        ));
        return new ProviderFetchResult(
                startDate + ":" + endDate,
                response.body(),
                response.status(),
                Instant.now(),
                response.retryCount(),
                response.quotaCost()
        );
    }

    /**
     * 解析已归档的赛果原始响应。
     *
     * <p>解析下沉到 Provider 是为了让写库路径与数据源无关：换源时解析器随之一起消失，
     * 不会在同步服务里留下跨源分支。</p>
     */
    @Override
    public List<SportteryMatchResultDto> parseMatchResults(
            String rawJson,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return matchResultPayloadMapper.parseItems(rawJson, startDate, endDate);
    }

    private ProviderHttpResponse call(String path, Map<String, String> params) {
        try {
            ProviderHttpResponse response = client.get(PROVIDER_CODE, path, params);
            if (!StringUtils.hasText(response.body())) {
                throw new SportteryDataAccessException("NovaAPI 返回空响应：" + path);
            }
            return response;
        } catch (ProviderHttpException exception) {
            throw new SportteryDataAccessException(
                    exception.category(),
                    exception.getMessage(),
                    exception
            );
        }
    }
}
