package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MatchDataAvailabilityEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 公开比赛分页列表项。 */
public record MatchListItemVo(
        /** 持久化比赛 ID。 */
        Long matchId,
        /** 竞彩业务日。 */
        LocalDate lotteryDate,
        String lotteryMatchNo,
        Long leagueId,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        OffsetDateTime kickoffTime,
        MatchStatusEnum matchStatus,
        /** 体彩官方让球，与亚洲让球线独立。 */
        BigDecimal officialHandicap,
        /** 体彩比赛池快照可用状态。 */
        MatchDataAvailabilityEnum sportteryAvailability,
        /** 最新体彩快照的 Provider 编码。 */
        String sportteryDataSource,
        OffsetDateTime sportteryCapturedAt,
        OffsetDateTime sportteryProviderUpdatedAt
) {
}
