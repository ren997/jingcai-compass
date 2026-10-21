package com.jingcaicompass.statistics.dto;

import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import java.time.LocalDate;

/**
 * 公开统计筛选；未给时间范围时返回截至当天的近 30 天。
 *
 * <p>{@code predictionType} 缺省为体彩，保持既有调用方（含旧前端）行为不变；
 * 亚盘与竞彩的命中口径、Brier 定义与 ROI 分母都不同，一次请求只计算一条产品线。
 */
public record StatisticsSummaryQueryDto(
        LocalDate startDate,
        LocalDate endDate,
        Long leagueId,
        String modelVersion,
        PredictionTypeEnum predictionType
) {

    public StatisticsSummaryQueryDto {
        predictionType = predictionType == null ? PredictionTypeEnum.SPORTTERY : predictionType;
    }

    /** 未指定产品线时的兼容构造器。 */
    public StatisticsSummaryQueryDto(
            LocalDate startDate,
            LocalDate endDate,
            Long leagueId,
            String modelVersion
    ) {
        this(startDate, endDate, leagueId, modelVersion, PredictionTypeEnum.SPORTTERY);
    }
}
