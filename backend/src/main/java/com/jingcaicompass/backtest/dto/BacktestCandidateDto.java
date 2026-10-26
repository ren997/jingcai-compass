package com.jingcaicompass.backtest.dto;

import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 一个回测候选场次。
 *
 * <p>字段刻意取自 {@code matches} 而不是「现查 Nova 赛程」：回测的对象是<b>我们自己的历史</b>，
 * 包括当时是否真的同步过赔率。用上游赛程重建候选会让「当时没采到的场次」凭空出现，
 * 于是回测结论不再对应线上可复现的条件。</p>
 *
 * @param externalMatchId NovaAPI 的 matchId；归档与历史赔率都只能用它定位
 */
public record BacktestCandidateDto(
        Long matchId,
        String externalMatchId,
        LocalDate lotteryDate,
        String lotteryMatchNo,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        Instant kickoffTime,
        MatchStatusEnum matchStatus
) {
}
