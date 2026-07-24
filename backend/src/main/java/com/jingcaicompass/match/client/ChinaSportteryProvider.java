package com.jingcaicompass.match.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.ChinaSportteryResponseDto;
import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpExecutor;
import com.jingcaicompass.system.provider.ProviderHttpRequest;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import com.jingcaicompass.system.provider.ProviderRetryPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "china", matchIfMissing = true)
public class ChinaSportteryProvider implements SportteryProvider {

    static final String MATCH_CALCULATOR_PATH =
            "/gateway/uniform/football/getMatchCalculatorV1.qry?channel=c&poolCode=hhad,had";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RestClient restClient;
    private final SportteryProviderProperties properties;
    private final ProviderHttpExecutor httpExecutor;
    private final ObjectMapper objectMapper;

    public ChinaSportteryProvider(
            @Qualifier("chinaSportteryRestClient") RestClient restClient,
            SportteryProviderProperties properties,
            ProviderHttpExecutor httpExecutor,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.httpExecutor = httpExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return "CHINA_SPORTTERY";
    }

    @Override
    public List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate) {
        ChinaSportteryResponseDto response = parseMatchPool(fetchMatchPoolRaw(lotteryDate).payloadJson());
        if (!response.success()) {
            throw new SportteryDataAccessException(
                    "中国体彩网比赛池接口返回失败：%s %s".formatted(
                            response.errorCode(), response.errorMessage()
                    )
            );
        }
        if (response.value() == null || response.value().matchInfoList() == null) {
            return List.of();
        }

        return response.value().matchInfoList().stream()
                .filter(group -> lotteryDate.toString().equals(group.businessDate()))
                .flatMap(group -> safeMatches(group).stream())
                .map(match -> toMatchDto(lotteryDate, match))
                .toList();
    }

    /**
     * 拉取比赛池原始响应，供同步模板写入 raw payload 与重试/额度计数。
     */
    @Override
    public ProviderFetchResult fetchMatchPoolRaw(LocalDate lotteryDate) {
        LocalDate requestDate = lotteryDate == null ? LocalDate.now(SHANGHAI) : lotteryDate;
        ProviderRetryPolicy policy = new ProviderRetryPolicy(
                properties.retry().maxAttempts(),
                properties.retry().delay()
        );
        try {
            ProviderHttpResponse response = httpExecutor.get(
                    restClient,
                    providerCode(),
                    ProviderHttpRequest.of(MATCH_CALCULATOR_PATH),
                    policy,
                    properties.quotaWarningThreshold()
            );
            if (!StringUtils.hasText(response.body())) {
                throw new SportteryDataAccessException("中国体彩网比赛池接口返回空响应");
            }
            return new ProviderFetchResult(
                    requestDate.toString(),
                    response.body(),
                    response.status(),
                    Instant.now(),
                    response.retryCount(),
                    response.quotaCost()
            );
        } catch (ProviderHttpException exception) {
            throw toSportteryException(exception);
        } catch (SportteryDataAccessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SportteryDataAccessException("读取中国体彩网比赛池失败", exception);
        }
    }

    /**
     * 真实赛果接口接入前返回空列表；赛果同步任务（T401）再补齐。
     */
    @Override
    public List<SportteryMatchResultDto> fetchMatchResults(LocalDate startDate, LocalDate endDate) {
        return List.of();
    }

    private ChinaSportteryResponseDto parseMatchPool(String payloadJson) {
        try {
            ChinaSportteryResponseDto response = objectMapper.readValue(
                    payloadJson, ChinaSportteryResponseDto.class
            );
            if (response == null) {
                throw new SportteryDataAccessException("中国体彩网比赛池接口返回空响应");
            }
            return response;
        } catch (SportteryDataAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法解析中国体彩网比赛池响应",
                    exception
            );
        }
    }

    private SportteryDataAccessException toSportteryException(ProviderHttpException exception) {
        return new SportteryDataAccessException(
                exception.category(),
                exception.getMessage(),
                exception
        );
    }

    private List<ChinaSportteryResponseDto.MatchDto> safeMatches(
            ChinaSportteryResponseDto.MatchGroupDto group
    ) {
        return group.subMatchList() == null ? List.of() : group.subMatchList();
    }

    private SportteryMatchDto toMatchDto(
            LocalDate lotteryDate,
            ChinaSportteryResponseDto.MatchDto match
    ) {
        LocalDate matchDate = LocalDate.parse(match.matchDate());
        LocalTime matchTime = LocalTime.parse(match.matchTime());
        return new SportteryMatchDto(
                "sporttery-%d".formatted(match.matchId()),
                lotteryDate,
                match.matchNumStr(),
                match.leagueAbbName(),
                match.homeTeamAbbName(),
                match.awayTeamAbbName(),
                matchDate.atTime(matchTime).atZone(SHANGHAI).toOffsetDateTime(),
                parseOfficialHandicap(match),
                toMatchStatus(match.matchStatus())
        );
    }

    private Integer parseOfficialHandicap(ChinaSportteryResponseDto.MatchDto match) {
        if (match.hhad() == null || !StringUtils.hasText(match.hhad().goalLine())) {
            return null;
        }
        try {
            return Integer.valueOf(match.hhad().goalLine());
        } catch (NumberFormatException exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法解析比赛 %d 的体彩让球值：%s".formatted(
                            match.matchId(), match.hhad().goalLine()
                    ),
                    exception
            );
        }
    }

    private MatchStatusEnum toMatchStatus(String matchStatus) {
        if (!StringUtils.hasText(matchStatus)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "比赛状态缺失"
            );
        }
        if ("Selling".equalsIgnoreCase(matchStatus)) {
            return MatchStatusEnum.SCHEDULED;
        }
        if ("Closed".equalsIgnoreCase(matchStatus) || "Lock".equalsIgnoreCase(matchStatus)) {
            return MatchStatusEnum.LOCKED;
        }
        // 未知状态按已截止处理，避免阻断整日比赛池；仍保留可观测映射。
        return MatchStatusEnum.LOCKED;
    }
}
