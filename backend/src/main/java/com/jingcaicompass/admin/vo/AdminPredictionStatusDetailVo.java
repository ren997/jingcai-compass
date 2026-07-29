package com.jingcaicompass.admin.vo;

import java.util.List;

/** 单条运营预测的当前状态及不可变事实、结算历史。 */
public record AdminPredictionStatusDetailVo(
        AdminPredictionStatusItemVo prediction,
        List<AdminResultFactVo> resultFactHistory,
        List<AdminSettlementMarketHistoryVo> settlementMarkets
) {
    public AdminPredictionStatusDetailVo {
        resultFactHistory = List.copyOf(resultFactHistory);
        settlementMarkets = List.copyOf(settlementMarkets);
    }
}
