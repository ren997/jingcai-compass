package com.jingcaicompass.odds.dto;

/**
 * 亚盘供应商联赛。
 */
public record AsianOddsLeagueDto(
        /** 供应商侧联赛 ID。 */
        String leagueId,
        /** 联赛名称。 */
        String leagueName,
        /** 运动类型，例如 soccer。 */
        String sportKey
) {
}
