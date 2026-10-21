package com.jingcaicompass.match.client;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.match.service.NovaSchedulePayloadMapper;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
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
 * <p>本类目前只实现赛程部分；赛果在阶段 4 补齐（当前抛错，与旧 provider 行为一致，
 * 因此切换开关不会让链路「看起来成功但其实没数据」）。</p>
 */
@Component
@ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "nova")
public class NovaSportteryProvider implements SportteryProvider {

    public static final String PROVIDER_CODE = "NOVA_API";

    static final String SCHEDULE_PATH = "/nova/sports-lottery/all-list";
    static final String MATCH_RESULTS_PATH = "/nova/text/match-results";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final NovaApiClient client;
    private final NovaSchedulePayloadMapper schedulePayloadMapper;

    public NovaSportteryProvider(
            NovaApiClient client,
            NovaSchedulePayloadMapper schedulePayloadMapper
    ) {
        this.client = client;
        this.schedulePayloadMapper = schedulePayloadMapper;
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
     * 阶段 4 实现。这里保持抛错而不是返回空结果：空结果会被同步模板当作
     * 「当天没有赛果」正常收尾，导致结算永远静默不执行。
     */
    @Override
    public ProviderFetchResult fetchMatchResultsRaw(LocalDate startDate, LocalDate endDate) {
        throw new SportteryDataAccessException(
                ProviderErrorCategory.UPSTREAM_FAILURE,
                "NovaAPI 赛果同步尚未启用（阶段 4）"
        );
    }

    @Override
    public List<SportteryMatchResultDto> parseMatchResults(
            String rawJson,
            LocalDate startDate,
            LocalDate endDate
    ) {
        throw new SportteryDataAccessException(
                ProviderErrorCategory.PARSE_FAILURE,
                "NovaAPI 赛果解析尚未启用（阶段 4）"
        );
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
