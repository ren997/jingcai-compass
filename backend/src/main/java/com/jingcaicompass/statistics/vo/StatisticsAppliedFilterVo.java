package com.jingcaicompass.statistics.vo;

/** 服务实际使用的非时间筛选条件。 */
public record StatisticsAppliedFilterVo(
        Long leagueId,
        String modelVersion
) {
}
