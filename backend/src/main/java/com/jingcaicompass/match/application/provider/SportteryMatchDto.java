package com.jingcaicompass.match.application.provider;

import com.jingcaicompass.match.domain.MatchStatus;

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
        MatchStatus matchStatus
) {
}
