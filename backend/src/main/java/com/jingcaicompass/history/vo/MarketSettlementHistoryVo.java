package com.jingcaicompass.history.vo;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.util.List;

/** 单个市场的完整结算链和查询时的当前状态。 */
public record MarketSettlementHistoryVo(
        MarketTypeEnum marketType,
        SettlementStatusEnum currentStatus,
        boolean currentSettlementPersisted,
        boolean recalculatedAfterFactCorrection,
        List<SettlementVersionVo> versions
) {
    public MarketSettlementHistoryVo {
        versions = List.copyOf(versions);
    }
}
