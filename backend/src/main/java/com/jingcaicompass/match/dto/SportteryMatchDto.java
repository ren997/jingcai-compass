package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MatchStatusEnum;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record SportteryMatchDto(
        String matchId,
        LocalDate lotteryDate,
        String lotteryMatchNo,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        OffsetDateTime kickoffTime,
        Integer officialHandicap,
        MatchStatusEnum matchStatus
) {
}
