package com.jingcaicompass.prediction.vo;

import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.time.Instant;

/**
 * 预测发布结果。
 *
 * @param predictionId 预测 ID
 * @param matchId 内部比赛 ID
 * @param modelVersion 模型版本
 * @param predictionVersion 同比赛模型下的发布版本
 * @param predictionStatus 当前发布生命周期状态
 * @param publishTime 首次发布时间
 * @param lockTime 锁定时间
 * @param predictionHash 规范化发布内容 SHA-256
 * @param reused 是否为已发布结果的幂等复用
 */
public record PredictionPublishResultVo(
        Long predictionId,
        Long matchId,
        String modelVersion,
        Integer predictionVersion,
        PredictionStatusEnum predictionStatus,
        Instant publishTime,
        Instant lockTime,
        String predictionHash,
        boolean reused
) {
}
