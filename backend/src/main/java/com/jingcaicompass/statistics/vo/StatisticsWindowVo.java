package com.jingcaicompass.statistics.vo;

import java.time.LocalDate;

/** 有明确起止日期的汇总窗口。 */
public record StatisticsWindowVo(
        LocalDate startDate,
        LocalDate endDate,
        StatisticsMetricsVo metrics
) {
}
