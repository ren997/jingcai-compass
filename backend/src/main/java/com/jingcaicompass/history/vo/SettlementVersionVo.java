package com.jingcaicompass.history.vo;

import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条不可变结算版本，可通过 matchFactId 重建其赛果输入。
 *
 * <p>{@code profit} 为该版本按单注本金 1 单位折算的净收益，只有亚盘市场才有值；
 * 体彩市场没有冻结水位，收益列为空（与 {@code ck_settlements_profit_scope} 一致）。
 */
public record SettlementVersionVo(
        Long settlementId,
        Integer settlementVersion,
        Integer supersedesSettlementVersion,
        SettlementStatusEnum settlementStatus,
        Long matchFactId,
        String ruleVersion,
        boolean current,
        Instant createdAt,
        BigDecimal profit
) {

    /** 未携带收益列的兼容构造器（体彩市场与 V23 之前的结算行）。 */
    public SettlementVersionVo(
            Long settlementId,
            Integer settlementVersion,
            Integer supersedesSettlementVersion,
            SettlementStatusEnum settlementStatus,
            Long matchFactId,
            String ruleVersion,
            boolean current,
            Instant createdAt
    ) {
        this(
                settlementId,
                settlementVersion,
                supersedesSettlementVersion,
                settlementStatus,
                matchFactId,
                ruleVersion,
                current,
                createdAt,
                null
        );
    }
}
