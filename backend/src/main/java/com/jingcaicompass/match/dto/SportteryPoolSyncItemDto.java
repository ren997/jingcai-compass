package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 体彩比赛池同步条目，含 HAD/HHAD SP 与原始销售状态。
 */
public record SportteryPoolSyncItemDto(
        /** 供应商侧比赛 ID（体彩 matchId）。 */
        String externalMatchId,
        LocalDate lotteryDate,
        String lotteryMatchNo,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        OffsetDateTime kickoffTime,
        BigDecimal officialHandicap,
        MatchStatusEnum matchStatus,
        /** 原始销售状态串，如 Selling。 */
        String sellStatus,
        BigDecimal hadHomeSp,
        BigDecimal hadDrawSp,
        BigDecimal hadAwaySp,
        BigDecimal hhadHomeSp,
        BigDecimal hhadDrawSp,
        BigDecimal hhadAwaySp
) {
}
