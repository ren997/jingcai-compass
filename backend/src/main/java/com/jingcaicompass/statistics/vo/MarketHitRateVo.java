package com.jingcaicompass.statistics.vo;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import java.math.BigDecimal;

/**
 * 单市场当前结算的命中统计。
 *
 * <p>亚盘存在走盘与半赢半输，单一「命中率」有歧义，因此并列两套口径：
 * <ul>
 *   <li>{@code hitRate = (HIT + HALF_WIN) / settledSampleSize}——不含走盘，回答「押出去的方向有几成真的是赢」；</li>
 *   <li>{@code weightedHitRate = (HIT + 0.5 × HALF_WIN) / settledSampleSize}——半盘按半注折算，
 *       与结算侧 0.25 刻度的 margin 判定同源，回答「按注码折算的有效命中比例」。</li>
 * </ul>
 *
 * <p>{@code settledSampleSize} 只含 {@code HIT ∪ MISS ∪ HALF_WIN ∪ HALF_LOSS}，**不含 PUSH**：
 * 走盘既没赢也没输，放进命中率分母会让两套口径同时失真。走盘单独由 {@code pushCount} 表达。
 */
public record MarketHitRateVo(
        MarketTypeEnum marketType,
        long settledSampleSize,
        long hitCount,
        long missCount,
        long pushCount,
        long halfWinCount,
        long halfLossCount,
        long pendingCount,
        long voidCount,
        BigDecimal hitRate,
        BigDecimal weightedHitRate
) {
}
