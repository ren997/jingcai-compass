package com.jingcaicompass.admin.vo;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;

/** 单个市场的当前结算投影；缺失结算会明确派生为 PENDING。 */
public record AdminSettlementMarketVo(
        MarketTypeEnum marketType,
        SettlementStatusEnum currentStatus,
        boolean currentSettlementPersisted,
        Long settlementId,
        Integer settlementVersion,
        Long matchFactId,
        String ruleVersion,
        boolean stale
) {
}
