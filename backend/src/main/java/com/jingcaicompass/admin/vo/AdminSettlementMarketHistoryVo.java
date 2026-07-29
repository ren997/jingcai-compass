package com.jingcaicompass.admin.vo;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.util.List;

/** 一个市场的当前状态和完整版本链。 */
public record AdminSettlementMarketHistoryVo(
        MarketTypeEnum marketType,
        SettlementStatusEnum currentStatus,
        boolean currentSettlementPersisted,
        boolean currentSettlementStale,
        List<AdminSettlementVersionVo> versions
) {
    public AdminSettlementMarketHistoryVo {
        versions = List.copyOf(versions);
    }
}
