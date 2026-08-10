package com.jingcaicompass.settlement.service;

import com.jingcaicompass.settlement.dto.SettlementRecalculationBatchResultDto;

/** 扫描并替代引用已被官方赛果修正的当前结算。 */
public interface SettlementRecalculationService {

    /**
     * 有界扫描已锁定预测的过期当前结算，并逐条在独立事务中重算。
     *
     * @param batchSize 本轮最多处理的预测数量
     * @return 本轮真实处理摘要
     */
    SettlementRecalculationBatchResultDto recalculateOutdatedSettlements(int batchSize);

    /**
     * 仅重算指定比赛引用旧赛果事实的锁定预测，不扫描其他比赛。
     *
     * @param matchId 比赛 ID
     * @return 该比赛的处理摘要
     */
    SettlementRecalculationBatchResultDto recalculateOutdatedSettlementsForMatch(Long matchId);
}
