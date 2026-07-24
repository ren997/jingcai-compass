package com.jingcaicompass.match.dto;

import java.time.Instant;

/**
 * 双源比赛自动映射入参。
 *
 * @param providerCode 供应商编码
 * @param externalMatchId 供应商侧比赛 ID
 * @param externalLeagueId 供应商侧联赛 ID，可空
 * @param externalHomeTeamId 供应商侧主队 ID，可空
 * @param externalAwayTeamId 供应商侧客队 ID，可空
 * @param leagueId 已解析标准联赛 ID，可空
 * @param homeTeamId 已解析标准主队 ID，可空
 * @param awayTeamId 已解析标准客队 ID，可空
 * @param leagueName 联赛展示名，可空
 * @param homeTeamName 主队展示名，可空
 * @param awayTeamName 客队展示名，可空
 * @param kickoffTime 开赛时间（必填）
 */
public record MatchMapRequestDto(
        String providerCode,
        String externalMatchId,
        String externalLeagueId,
        String externalHomeTeamId,
        String externalAwayTeamId,
        Long leagueId,
        Long homeTeamId,
        Long awayTeamId,
        String leagueName,
        String homeTeamName,
        String awayTeamName,
        Instant kickoffTime
) {
}
