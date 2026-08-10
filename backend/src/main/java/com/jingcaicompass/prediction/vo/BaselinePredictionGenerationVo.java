package com.jingcaicompass.prediction.vo;

import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** 管理员触发首版预测基线后的可读运行摘要。 */
public record BaselinePredictionGenerationVo(
        /** 生成目标的竞彩业务日。 */
        LocalDate lotteryDate,
        /** 固定的可解释模型版本。 */
        String modelVersion,
        /** 固定的输入特征定义版本。 */
        String featureVersion,
        /** 已读取的比赛总数。 */
        int candidateCount,
        /** 生成且已导入 DRAFT 的预测数。 */
        int generatedCount,
        /** 此次新插入的 DRAFT 数。 */
        int insertedCount,
        /** 相同批次幂等复用的 DRAFT 数。 */
        int reusedCount,
        /** 严格 UTF-8 生成批次标识；没有可预测比赛时为空。 */
        String generationBatchId,
        /** 生成 JSON 原始字节的 SHA-256；没有可预测比赛时为空。 */
        String generationBatchHash,
        /** 由已持久化特征快照派生的生成时刻；没有可预测比赛时为空。 */
        Instant generatedAt,
        /** 按稳定原因聚合的跳过数量。 */
        Map<BaselinePredictionSkipReasonEnum, Integer> skippedByReason
) {
    public BaselinePredictionGenerationVo {
        skippedByReason = skippedByReason == null ? Map.of() : Map.copyOf(skippedByReason);
    }
}
