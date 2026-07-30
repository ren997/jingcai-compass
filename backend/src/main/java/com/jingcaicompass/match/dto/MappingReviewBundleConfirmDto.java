package com.jingcaicompass.match.dto;

/**
 * 一次赛事复核中可选地确认联赛与主客队标准化关系。
 *
 * @param mappingId 外部赛事映射 ID
 * @param targetMatchId 已持久化的竞彩比赛候选 ID
 * @param confirmLeague 是否同时确认外部联赛
 * @param confirmHomeTeam 是否同时确认外部主队
 * @param confirmAwayTeam 是否同时确认外部客队
 */
public record MappingReviewBundleConfirmDto(
        Long mappingId,
        Long targetMatchId,
        boolean confirmLeague,
        boolean confirmHomeTeam,
        boolean confirmAwayTeam
) {
}
