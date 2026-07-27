package com.jingcaicompass.settlement.dto;

/** 一次赛果修正结算重算批次的可观测结果。 */
public record SettlementRecalculationBatchResultDto(
        /** 从过期结算查询取得的锁定预测数量。 */
        int candidatePredictionCount,
        /** 成功追加至少一个结算替代版本的预测数量。 */
        int recalculatedPredictionCount,
        /** 本批追加的结算市场版本数量。 */
        int recalculatedMarketCount,
        /** 处理时已不再过期或不再具备资格的预测数量。 */
        int skippedPredictionCount,
        /** 系统异常导致整条预测替代回滚的数量。 */
        int failedPredictionCount,
        /** 规则或可追溯输入缺失而保留旧当前结算的数量。 */
        int manualReviewPredictionCount
) {
}
