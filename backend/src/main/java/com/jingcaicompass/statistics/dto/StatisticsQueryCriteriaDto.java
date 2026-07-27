package com.jingcaicompass.statistics.dto;

import java.time.LocalDate;

/** 归一化后的统计查询条件，仅供 Mapper 使用。 */
public record StatisticsQueryCriteriaDto(
        LocalDate startDate,
        LocalDate endDate,
        Long leagueId,
        String modelVersion
) {
}
