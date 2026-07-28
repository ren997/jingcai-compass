package com.jingcaicompass.prediction.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.snapshot.enums.PublicSnapshotAvailabilityEnum;
import java.math.BigDecimal;
import java.time.Instant;

/** 单个公开预测版本及其透明字段。 */
public record PredictionVersionVo(
        Long predictionId,
        Integer predictionVersion,
        /** 同模型的上一公开预测 ID；首版为空。 */
        Long replacesPredictionId,
        PredictionStatusEnum predictionStatus,
        String featureVersion,
        BigDecimal homeWinProb,
        BigDecimal drawProb,
        BigDecimal awayWinProb,
        HandicapPickEnum handicapPick,
        BigDecimal expectedTotalGoals,
        ConfidenceLevelEnum confidenceLevel,
        String analysisSummary,
        Instant generatedAt,
        Instant publishTime,
        Instant lockTime,
        String predictionHash,
        PublicSnapshotAvailabilityEnum snapshotAvailability,
        PredictionSnapshotVo snapshot
) {
}
