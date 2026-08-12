package com.jingcaicompass.admin.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 管理员状态页使用的比赛摘要。 */
public record AdminPredictionMatchVo(
        Long matchId,
        LocalDate lotteryDate,
        String lotteryMatchNo,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        Instant kickoffTime,
        /** 体彩让球胜平负使用的官方让球线。 */
        BigDecimal officialHandicap
) {
}
