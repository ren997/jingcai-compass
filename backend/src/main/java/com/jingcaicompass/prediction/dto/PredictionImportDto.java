package com.jingcaicompass.prediction.dto;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单场比赛的模型预测导入项。
 *
 * @param matchId 内部比赛 ID
 * @param modelVersion 模型版本
 * @param featureVersion 特征版本
 * @param homeWinProb 主胜概率
 * @param drawProb 平局概率
 * @param awayWinProb 客胜概率
 * @param handicapPick 让球胜平负倾向
 * @param expectedTotalGoals 预期总进球
 * @param confidenceLevel 模型置信等级
 * @param analysisSummary 面向用户的分析摘要
 * @param generatedAt 模型生成时间
 * @param asianOddsSnapshotId 生成时使用的已确认完整亚盘快照 ID；亚盘方向为空时为空
 * @param asianHandicapPick 亚盘让球赢盘方向
 * @param totalGoalsPick 亚盘大小球方向
 */
public record PredictionImportDto(
        Long matchId,
        String modelVersion,
        String featureVersion,
        BigDecimal homeWinProb,
        BigDecimal drawProb,
        BigDecimal awayWinProb,
        HandicapPickEnum handicapPick,
        BigDecimal expectedTotalGoals,
        ConfidenceLevelEnum confidenceLevel,
        String analysisSummary,
        Instant generatedAt,
        Long asianOddsSnapshotId,
        AsianHandicapPickEnum asianHandicapPick,
        TotalGoalsPickEnum totalGoalsPick
) {

    /** 兼容不含亚盘方向字段的历史 V1 导入记录。 */
    public PredictionImportDto(
            Long matchId,
            String modelVersion,
            String featureVersion,
            BigDecimal homeWinProb,
            BigDecimal drawProb,
            BigDecimal awayWinProb,
            HandicapPickEnum handicapPick,
            BigDecimal expectedTotalGoals,
            ConfidenceLevelEnum confidenceLevel,
            String analysisSummary,
            Instant generatedAt
    ) {
        this(
                matchId,
                modelVersion,
                featureVersion,
                homeWinProb,
                drawProb,
                awayWinProb,
                handicapPick,
                expectedTotalGoals,
                confidenceLevel,
                analysisSummary,
                generatedAt,
                null,
                null,
                null
        );
    }
}
