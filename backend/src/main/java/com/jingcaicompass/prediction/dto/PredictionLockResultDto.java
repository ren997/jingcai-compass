package com.jingcaicompass.prediction.dto;

import java.util.List;

/**
 * 一次预测锁定批次的执行结果。
 *
 * @param lockedCount 成功锁定数量
 * @param failedCount 失败数量
 * @param lockedPredictionIds 成功锁定的预测 ID
 * @param failedPredictionIds 失败的预测 ID
 * @param durationMs 批次耗时，单位毫秒
 */
public record PredictionLockResultDto(
        int lockedCount,
        int failedCount,
        List<Long> lockedPredictionIds,
        List<Long> failedPredictionIds,
        long durationMs
) {

    public PredictionLockResultDto {
        lockedPredictionIds = List.copyOf(lockedPredictionIds);
        failedPredictionIds = List.copyOf(failedPredictionIds);
    }
}
