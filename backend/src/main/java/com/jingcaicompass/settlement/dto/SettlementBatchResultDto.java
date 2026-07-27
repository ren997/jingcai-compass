package com.jingcaicompass.settlement.dto;

/** 一次自动结算批次的可观测结果。 */
public record SettlementBatchResultDto(
        /** 从候选查询取得的锁定预测数量。 */
        int candidatePredictionCount,
        /** 成功至少写入一个市场结算的预测数量。 */
        int settledPredictionCount,
        /** 本批新增的结算市场数量。 */
        int settledMarketCount,
        /** 处理时已无待结算市场或已不再具备资格的预测数量。 */
        int skippedPredictionCount,
        /** 系统异常导致回滚、可由任务重试的预测数量。 */
        int failedPredictionCount,
        /** 缺少可人工补齐的结算输入而未写入结算的预测数量。 */
        int manualReviewPredictionCount
) {
}
