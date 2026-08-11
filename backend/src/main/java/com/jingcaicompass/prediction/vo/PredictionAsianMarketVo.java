package com.jingcaicompass.prediction.vo;

import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import java.math.BigDecimal;
import java.time.Instant;

/** 生成预测时绑定的完整亚盘主盘快照，供公开和后台复核。 */
public record PredictionAsianMarketVo(
        Long asianOddsSnapshotId,
        String providerCode,
        String bookmakerCode,
        BigDecimal handicapLine,
        BigDecimal homeOdds,
        BigDecimal awayOdds,
        BigDecimal totalLine,
        BigDecimal overOdds,
        BigDecimal underOdds,
        OddsSnapshotTypeEnum snapshotType,
        Instant capturedAt,
        Instant providerUpdatedAt
) {
}
