package com.jingcaicompass.history.vo;

import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.time.Instant;

/** 一条不可变结算版本，可通过 matchFactId 重建其赛果输入。 */
public record SettlementVersionVo(
        Long settlementId,
        Integer settlementVersion,
        Integer supersedesSettlementVersion,
        SettlementStatusEnum settlementStatus,
        Long matchFactId,
        String ruleVersion,
        boolean current,
        Instant createdAt
) {
}
