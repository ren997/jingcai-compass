package com.jingcaicompass.settlement.service;

/** 单条预测在独立重算事务中的结果。 */
record SettlementRecalculationWriteResult(Outcome outcome, int recalculatedMarketCount) {

    enum Outcome {
        RECALCULATED,
        SKIPPED
    }

    static SettlementRecalculationWriteResult recalculated(int recalculatedMarketCount) {
        return new SettlementRecalculationWriteResult(Outcome.RECALCULATED, recalculatedMarketCount);
    }

    static SettlementRecalculationWriteResult skipped() {
        return new SettlementRecalculationWriteResult(Outcome.SKIPPED, 0);
    }
}
