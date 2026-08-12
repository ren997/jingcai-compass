package com.jingcaicompass.admin.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.vo.PredictionAsianMarketVo;
import java.math.BigDecimal;
import java.time.Instant;

/** 管理员发布前复核的一条 DRAFT 预测。 */
public record AdminDraftPredictionListItemVo(
        Long predictionId,
        String modelVersion,
        String featureVersion,
        String generationBatchId,
        String generationBatchHash,
        Integer predictionVersion,
        BigDecimal homeWinProb,
        BigDecimal drawProb,
        BigDecimal awayWinProb,
        HandicapPickEnum handicapPick,
        BigDecimal expectedTotalGoals,
        AsianHandicapPickEnum asianHandicapPick,
        TotalGoalsPickEnum totalGoalsPick,
        PredictionAsianMarketVo asianMarket,
        PredictionTypeEnum predictionType,
        ConfidenceLevelEnum asianHandicapConfidenceLevel,
        ConfidenceLevelEnum totalGoalsConfidenceLevel,
        ConfidenceLevelEnum confidenceLevel,
        String analysisSummary,
        Instant generatedAt,
        AdminPredictionMatchVo match
) {

    /** 兼容缺少亚盘方向字段的历史草稿复核视图。 */
    public AdminDraftPredictionListItemVo(
            Long predictionId,
            String modelVersion,
            String featureVersion,
            String generationBatchId,
            String generationBatchHash,
            Integer predictionVersion,
            BigDecimal homeWinProb,
            BigDecimal drawProb,
            BigDecimal awayWinProb,
            HandicapPickEnum handicapPick,
            BigDecimal expectedTotalGoals,
            ConfidenceLevelEnum confidenceLevel,
            String analysisSummary,
            Instant generatedAt,
            AdminPredictionMatchVo match
    ) {
        this(
                predictionId,
                modelVersion,
                featureVersion,
                generationBatchId,
                generationBatchHash,
                predictionVersion,
                homeWinProb,
                drawProb,
                awayWinProb,
                handicapPick,
                expectedTotalGoals,
                null,
                null,
                null,
                PredictionTypeEnum.SPORTTERY,
                null,
                null,
                confidenceLevel,
                analysisSummary,
                generatedAt,
                match
        );
    }
}
