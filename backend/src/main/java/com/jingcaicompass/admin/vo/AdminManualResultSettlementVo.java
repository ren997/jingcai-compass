package com.jingcaicompass.admin.vo;

/** 人工赛果写入后、仅限目标比赛的结算与重算处理摘要。 */
public record AdminManualResultSettlementVo(
        int recalculationCandidatePredictionCount,
        int recalculatedPredictionCount,
        int recalculatedMarketCount,
        int recalculationFailureCount,
        int recalculationManualReviewCount,
        int settlementCandidatePredictionCount,
        int settledPredictionCount,
        int settledMarketCount,
        int settlementFailureCount,
        int settlementManualReviewCount
) {
}
