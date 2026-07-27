package com.jingcaicompass.settlement.service;

/** 单条预测在独立结算事务中的结果。 */
record SettlementWriteResult(Outcome outcome, int settledMarketCount) {

    enum Outcome {
        SETTLED,
        SKIPPED
    }

    static SettlementWriteResult settled(int settledMarketCount) {
        return new SettlementWriteResult(Outcome.SETTLED, settledMarketCount);
    }

    static SettlementWriteResult skipped() {
        return new SettlementWriteResult(Outcome.SKIPPED, 0);
    }
}
