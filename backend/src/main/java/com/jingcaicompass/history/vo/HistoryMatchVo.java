package com.jingcaicompass.history.vo;

import java.time.Instant;
import java.time.LocalDate;

/** 历史记录关联的比赛展示信息。 */
public record HistoryMatchVo(
        Long matchId,
        LocalDate lotteryDate,
        String lotteryMatchNo,
        Long leagueId,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        Instant kickoffTime
) {
}
