package com.jingcaicompass.statistics.vo;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import java.math.BigDecimal;

/** 单市场当前结算的命中统计。 */
public record MarketHitRateVo(
        MarketTypeEnum marketType,
        long settledSampleSize,
        long hitCount,
        long missCount,
        long pendingCount,
        long voidCount,
        BigDecimal hitRate
) {
}
