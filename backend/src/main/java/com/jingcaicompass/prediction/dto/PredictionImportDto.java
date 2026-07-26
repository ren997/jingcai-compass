package com.jingcaicompass.prediction.dto;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
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
        Instant generatedAt
) {
}
