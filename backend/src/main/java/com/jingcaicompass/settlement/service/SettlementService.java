package com.jingcaicompass.settlement.service;

import com.jingcaicompass.settlement.dto.SettlementBatchResultDto;

/** 扫描并追加已锁定预测的自动结算。 */
public interface SettlementService {

    /**
     * 结算最多指定数量的当前候选预测；每条预测在独立事务中处理。
     *
     * @param batchSize 单批最多处理的预测数
     * @return 批次成功、失败和人工处理摘要
     */
    SettlementBatchResultDto settlePendingPredictions(int batchSize);
}
