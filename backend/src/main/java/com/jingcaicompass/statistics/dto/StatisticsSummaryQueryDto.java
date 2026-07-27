package com.jingcaicompass.statistics.dto;

import java.time.LocalDate;

/** 公开统计筛选；未给时间范围时返回截至当天的近 30 天。 */
public record StatisticsSummaryQueryDto(
        LocalDate startDate,
        LocalDate endDate,
        Long leagueId,
        String modelVersion
) {
}
