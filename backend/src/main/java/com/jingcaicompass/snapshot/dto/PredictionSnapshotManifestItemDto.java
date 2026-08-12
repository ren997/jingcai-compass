package com.jingcaicompass.snapshot.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * manifest 内可复算单条预测哈希的完整公开内容。
 *
 * @param predictionHashSchemaVersion 单条预测哈希结构版本
 * @param predictionId 预测 ID
 * @param matchId 内部比赛 ID
 * @param modelVersion 模型版本
 * @param featureVersion 特征版本
 * @param generationBatchId 生成批次标识
 * @param generationBatchHash 生成批次 SHA-256
 * @param predictionVersion 同比赛模型的预测版本
 * @param homeWinProb 主胜概率
 * @param drawProb 平局概率
 * @param awayWinProb 客胜概率
 * @param handicapPick 让球胜平负倾向
 * @param expectedTotalGoals 预期总进球
 * @param asianOddsSnapshotId 生成时使用的亚盘快照 ID；历史 V1 预测为空
 * @param asianHandicapPick 亚盘让球赢盘方向；历史 V1 预测为空
 * @param totalGoalsPick 亚盘大小球方向；历史 V1 预测为空
 * @param confidenceLevel 置信等级
 * @param analysisSummary 合规公开分析摘要
 * @param generatedAt 模型生成时间，UTC 微秒格式
 * @param publishTime 首次发布时间，UTC 微秒格式
 * @param lockTime 锁定时间，UTC 微秒格式
 * @param predictionHash 单条规范化预测 SHA-256
 */
@JsonPropertyOrder({
        "predictionHashSchemaVersion",
        "predictionId",
        "matchId",
        "modelVersion",
        "featureVersion",
        "generationBatchId",
        "generationBatchHash",
        "predictionVersion",
        "predictionType",
        "homeWinProb",
        "drawProb",
        "awayWinProb",
        "handicapPick",
        "expectedTotalGoals",
        "asianOddsSnapshotId",
        "asianHandicapPick",
        "totalGoalsPick",
        "asianHandicapConfidenceLevel",
        "totalGoalsConfidenceLevel",
        "confidenceLevel",
        "analysisSummary",
        "generatedAt",
        "publishTime",
        "lockTime",
        "predictionHash"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PredictionSnapshotManifestItemDto(
        int predictionHashSchemaVersion,
        Long predictionId,
        Long matchId,
        String modelVersion,
        String featureVersion,
        String generationBatchId,
        String generationBatchHash,
        Integer predictionVersion,
        String predictionType,
        BigDecimal homeWinProb,
        BigDecimal drawProb,
        BigDecimal awayWinProb,
        String handicapPick,
        BigDecimal expectedTotalGoals,
        Long asianOddsSnapshotId,
        String asianHandicapPick,
        String totalGoalsPick,
        String asianHandicapConfidenceLevel,
        String totalGoalsConfidenceLevel,
        String confidenceLevel,
        String analysisSummary,
        String generatedAt,
        String publishTime,
        String lockTime,
        String predictionHash
) {
}
