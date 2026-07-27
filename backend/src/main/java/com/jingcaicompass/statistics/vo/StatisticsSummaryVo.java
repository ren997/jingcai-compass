package com.jingcaicompass.statistics.vo;

import java.time.LocalDate;
import java.util.List;

/** 公开统计响应，包含请求范围和固定的近 7/30 天对照窗口。 */
public record StatisticsSummaryVo(
        LocalDate asOfDate,
        StatisticsAppliedFilterVo appliedFilter,
        StatisticsWindowVo requestedWindow,
        StatisticsWindowVo trailingSevenDays,
        StatisticsWindowVo trailingThirtyDays,
        List<LeagueStatisticsVo> byLeague,
        List<ModelVersionStatisticsVo> byModelVersion
) {
    public StatisticsSummaryVo {
        byLeague = List.copyOf(byLeague);
        byModelVersion = List.copyOf(byModelVersion);
    }
}
