package com.jingcaicompass.statistics.vo;

/** 某一时间窗口或分组的统一统计口径。 */
public record StatisticsMetricsVo(
        long lockedPredictionCount,
        long finalFactCount,
        long pendingFactCount,
        long voidFactCount,
        ProbabilityMetricsVo probabilityMetrics,
        MarketHitRateVo had,
        MarketHitRateVo hhad,
        RoiMetricsVo roi
) {
}
