package com.jingcaicompass.backtest.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 一个参数组在一个市场口径下的指标。
 *
 * <p>{@code hitRate} 与 {@code weightedHitRate} 并列返回是刻意的：两者差异大说明这批样本里
 * 四分盘（.25/.75）很多，而「差异大」本身不是问题。只给一个数会让读者误以为口径统一。</p>
 *
 * <p>{@code brierDelta = brierMarket - brierModel}：<b>正数才表示模型优于市场</b>。
 * 这个方向不能省：Brier 是越低越好，读者按「差值越大越好」理解时，
 * 若返回的是 {@code model - market}，结论会整体反掉。</p>
 */
public record BacktestMetricsVo(
        Long runId,
        String groupKey,
        String marketScope,
        Integer caseCount,
        Integer decidedCount,
        Integer skippedCount,
        Integer pickCount,
        Integer settledCount,
        Integer pushCount,
        Integer halfWinCount,
        Integer halfLossCount,
        Integer hitCount,
        Integer missCount,
        BigDecimal hitRate,
        BigDecimal weightedHitRate,
        BigDecimal profitSum,
        BigDecimal roi,
        BigDecimal yieldRate,
        BigDecimal brierModel,
        BigDecimal brierMarket,
        BigDecimal brierDelta,
        Integer brierSampleSize,
        Map<String, Object> skipReasonCounts,
        Map<String, Object> metricsJson,
        Instant computedAt
) {
}
