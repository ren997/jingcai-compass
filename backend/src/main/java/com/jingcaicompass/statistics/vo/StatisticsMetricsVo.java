package com.jingcaicompass.statistics.vo;

import java.util.List;

/**
 * 某一时间窗口或分组的统一统计口径。
 *
 * <p>{@code markets} 只包含当前 {@code predictionType} 对应的市场，顺序即
 * {@code MarketTypeEnum.marketsFor(type)} 的顺序。用数组而不是 {@code had}/{@code hhad} 两个具名字段：
 * 具名字段会把「HAD/HHAD 必然存在」写进响应形状，亚盘预测只能在另一侧留空，
 * 混算风险就从计算层转移到了展示层。
 */
public record StatisticsMetricsVo(
        long lockedPredictionCount,
        long finalFactCount,
        long pendingFactCount,
        long voidFactCount,
        List<MarketHitRateVo> markets,
        ProbabilityMetricsVo probabilityMetrics,
        RoiMetricsVo roi
) {
    public StatisticsMetricsVo {
        markets = List.copyOf(markets);
    }
}
