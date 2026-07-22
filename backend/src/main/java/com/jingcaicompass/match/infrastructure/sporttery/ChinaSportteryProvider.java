package com.jingcaicompass.match.infrastructure.sporttery;

import com.jingcaicompass.match.application.provider.SportteryMatchDto;
import com.jingcaicompass.match.application.provider.SportteryProvider;
import com.jingcaicompass.match.domain.MatchStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

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

    public ChinaSportteryProvider(
            @Qualifier("chinaSportteryRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    @Override
    public String providerCode() {
        return "CHINA_SPORTTERY";
    }

    @Override
    public List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate) {
        ChinaSportteryResponseDto response = requestMatchPool();
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

    private ChinaSportteryResponseDto requestMatchPool() {
        try {
            ChinaSportteryResponseDto response = restClient.get()
                    .uri(MATCH_CALCULATOR_PATH)
                    .retrieve()
                    .body(ChinaSportteryResponseDto.class);
            if (response == null) {
                throw new SportteryDataAccessException("中国体彩网比赛池接口返回空响应");
            }
            return response;
        } catch (SportteryDataAccessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SportteryDataAccessException("读取中国体彩网比赛池失败", exception);
        }
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
                    "无法解析比赛 %d 的体彩让球值：%s".formatted(
                            match.matchId(), match.hhad().goalLine()
                    ),
                    exception
            );
        }
    }

    private MatchStatus toMatchStatus(String matchStatus) {
        return "Selling".equalsIgnoreCase(matchStatus)
                ? MatchStatus.SCHEDULED
                : MatchStatus.LOCKED;
    }
}
