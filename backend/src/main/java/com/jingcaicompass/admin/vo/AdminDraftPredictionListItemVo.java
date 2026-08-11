package com.jingcaicompass.admin.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
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
        ConfidenceLevelEnum confidenceLevel,
        String analysisSummary,
        Instant generatedAt,
        AdminPredictionMatchVo match
) {
}
