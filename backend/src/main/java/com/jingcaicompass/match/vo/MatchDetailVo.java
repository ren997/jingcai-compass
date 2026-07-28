package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MatchDataAvailabilityEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 公开比赛基础资料、当前盘口和映射透明信息。 */
public record MatchDetailVo(
        /** 持久化比赛 ID。 */
        Long matchId,
        LocalDate lotteryDate,
        String lotteryMatchNo,
        Long leagueId,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        OffsetDateTime kickoffTime,
        MatchStatusEnum matchStatus,
        Integer homeScore,
        Integer awayScore,
        /** 当前最新体彩市场快照。 */
        SportteryMarketVo sportteryMarket,
        /** 亚盘市场整体可用状态。 */
        MatchDataAvailabilityEnum asianOddsAvailability,
        /** 各来源、公司和让球线的当前亚盘。 */
        List<AsianOddsMarketVo> asianOddsMarkets,
        /** 来源映射整体可用状态。 */
        MatchDataAvailabilityEnum mappingAvailability,
        List<MatchSourceMappingVo> sourceMappings
) {
}
