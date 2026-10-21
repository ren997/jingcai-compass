package com.jingcaicompass.admin.vo;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;

/**
 * 单个市场的当前结算投影；缺失结算会明确派生为 PENDING。
 *
 * <p>{@code profit} 只有亚盘市场才有值（单注本金 1 单位折算），体彩市场为 {@code null}——
 * 与 {@code ck_settlements_profit_scope} 一致，不要在这里补 0。
 */
public record AdminSettlementMarketVo(
        MarketTypeEnum marketType,
        SettlementStatusEnum currentStatus,
        boolean currentSettlementPersisted,
        Long settlementId,
        Integer settlementVersion,
        Long matchFactId,
        String ruleVersion,
        boolean stale,
        BigDecimal profit
) {

    /** 不携带收益列的兼容构造器。 */
    public AdminSettlementMarketVo(
            MarketTypeEnum marketType,
            SettlementStatusEnum currentStatus,
            boolean currentSettlementPersisted,
            Long settlementId,
            Integer settlementVersion,
            Long matchFactId,
            String ruleVersion,
            boolean stale
    ) {
        this(marketType, currentStatus, currentSettlementPersisted, settlementId, settlementVersion,
                matchFactId, ruleVersion, stale, null);
    }
}
