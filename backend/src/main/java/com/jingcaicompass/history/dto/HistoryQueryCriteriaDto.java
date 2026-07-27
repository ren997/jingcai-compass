package com.jingcaicompass.history.dto;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.time.LocalDate;
import java.util.Set;

/** 归一化后的历史查询条件，仅供 Mapper 使用。 */
public record HistoryQueryCriteriaDto(
        LocalDate startDate,
        LocalDate endDate,
        Long leagueId,
        String modelVersion,
        boolean lockedOnly,
        MarketTypeEnum settlementMarket,
        boolean hasSettlementStatusFilter,
        boolean pendingStatusRequested,
        Set<SettlementStatusEnum> persistedSettlementStatuses,
        long pageSize,
        long offset
) {
}
