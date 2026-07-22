package com.jingcaicompass.match.application;

import com.jingcaicompass.match.api.vo.MatchSummaryVo;
import com.jingcaicompass.match.domain.MatchStatus;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class MatchQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    public List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate) {
        String weekday = weekdayLabel(lotteryDate.getDayOfWeek());
        return List.of(
                createMatch(lotteryDate, weekday, 1, "英超", "曼彻斯特城", "阿森纳", LocalTime.of(19, 30), -1),
                createMatch(lotteryDate, weekday, 2, "西甲", "皇家马德里", "巴塞罗那", LocalTime.of(21, 0), 0),
                createMatch(lotteryDate, weekday, 3, "意甲", "国际米兰", "AC米兰", LocalTime.of(22, 45), -1)
        );
    }

    public LocalDate currentLotteryDate() {
        return LocalDate.now(SHANGHAI);
    }

    private MatchSummaryVo createMatch(
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
        return new MatchSummaryVo(
                "stub-%s-%03d".formatted(lotteryDate, sequence),
                lotteryDate,
                "%s%03d".formatted(weekday, sequence),
                leagueName,
                homeTeamName,
                awayTeamName,
                lotteryDate.atTime(kickoffTime).atOffset(offset),
                officialHandicap,
                MatchStatus.SCHEDULED,
                "STUB"
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
