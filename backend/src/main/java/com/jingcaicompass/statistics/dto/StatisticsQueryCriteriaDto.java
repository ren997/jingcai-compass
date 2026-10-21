package com.jingcaicompass.statistics.dto;

import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import java.time.LocalDate;

/**
 * 归一化后的统计查询条件，仅供 Mapper 使用。
 *
 * <p>{@code predictionType} 为 {@code null} 时按体彩处理：V21 之前的预测行没有该列值，
 * 且统计口径必须保持向后兼容。竞彩与亚盘是两套不同的结算语义，绝不能在同一个分母里混算。
 */
public record StatisticsQueryCriteriaDto(
        LocalDate startDate,
        LocalDate endDate,
        Long leagueId,
        String modelVersion,
        PredictionTypeEnum predictionType
) {

    public StatisticsQueryCriteriaDto {
        predictionType = predictionType == null ? PredictionTypeEnum.SPORTTERY : predictionType;
    }

    /** 未指定产品线时的兼容构造器。 */
    public StatisticsQueryCriteriaDto(
            LocalDate startDate,
            LocalDate endDate,
            Long leagueId,
            String modelVersion
    ) {
        this(startDate, endDate, leagueId, modelVersion, PredictionTypeEnum.SPORTTERY);
    }
}
