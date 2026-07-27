package com.jingcaicompass.history.dto;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.time.LocalDate;
import java.util.Set;

/** 公开历史列表筛选；日期按竞彩业务日解释。 */
public record HistoryListQueryDto(
        LocalDate startDate,
        LocalDate endDate,
        Long leagueId,
        String modelVersion,
        Boolean lockedOnly,
        MarketTypeEnum settlementMarket,
        Set<SettlementStatusEnum> settlementStatuses,
        Integer pageNo,
        Integer pageSize
) {
}
