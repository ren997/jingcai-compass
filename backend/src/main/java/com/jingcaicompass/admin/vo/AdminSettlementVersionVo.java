package com.jingcaicompass.admin.vo;

import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.time.Instant;

/** 一条不可变结算版本的后台追溯视图。 */
public record AdminSettlementVersionVo(
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
