package com.jingcaicompass.statistics.vo;

/** 请求窗口内按联赛汇总的统计。 */
public record LeagueStatisticsVo(
        Long leagueId,
        String leagueName,
        StatisticsMetricsVo metrics
) {
}
