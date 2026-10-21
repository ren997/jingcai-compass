package com.jingcaicompass.history.dto;

import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.time.LocalDate;
import java.util.Set;

/**
 * 归一化后的历史查询条件，仅供 Mapper 使用。
 *
 * <p>{@code predictionType} 归一化后不为空：缺省时已由 {@code HistoryQueryServiceImpl} 按市场反推，
 * 或直接取体彩，因此 Mapper 里可以无条件按类型过滤，不必写 NULL 兼容分支。
 */
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
        long offset,
        PredictionTypeEnum predictionType
) {

    /** 未指定产品线时的兼容构造器，按体彩处理。 */
    public HistoryQueryCriteriaDto(
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
        this(
                startDate,
                endDate,
                leagueId,
                modelVersion,
                lockedOnly,
                settlementMarket,
                hasSettlementStatusFilter,
                pendingStatusRequested,
                persistedSettlementStatuses,
                pageSize,
                offset,
                PredictionTypeEnum.SPORTTERY
        );
    }
}
