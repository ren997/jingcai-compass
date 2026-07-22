package com.jingcaicompass.match.infrastructure.sporttery;

import com.jingcaicompass.match.application.provider.SportteryMatchDto;
import com.jingcaicompass.match.application.provider.SportteryProvider;
import com.jingcaicompass.match.domain.MatchStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "stub")
public class StubSportteryProvider implements SportteryProvider {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Override
    public String providerCode() {
        return "STUB";
    }

    @Override
    public List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate) {
        String weekday = weekdayLabel(lotteryDate.getDayOfWeek());
        return List.of(
                createMatch(lotteryDate, weekday, 1, "演示联赛", "演示主队 A", "演示客队 A", LocalTime.of(19, 30), -1),
                createMatch(lotteryDate, weekday, 2, "演示联赛", "演示主队 B", "演示客队 B", LocalTime.of(21, 0), 0),
                createMatch(lotteryDate, weekday, 3, "演示联赛", "演示主队 C", "演示客队 C", LocalTime.of(22, 45), 1)
        );
    }

    private SportteryMatchDto createMatch(
            LocalDate lotteryDate,
            String weekday,
            int sequence,
            String leagueName,
            String homeTeamName,
            String awayTeamName,
            LocalTime kickoffTime,
            int officialHandicap
    ) {
        ZoneOffset offset = SHANGHAI.getRules().getOffset(lotteryDate.atTime(kickoffTime));
        return new SportteryMatchDto(
                "stub-%s-%03d".formatted(lotteryDate, sequence),
                lotteryDate,
                "%s%03d".formatted(weekday, sequence),
                leagueName,
                homeTeamName,
                awayTeamName,
                lotteryDate.atTime(kickoffTime).atOffset(offset),
                officialHandicap,
                MatchStatus.SCHEDULED
        );
    }

    private String weekdayLabel(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
