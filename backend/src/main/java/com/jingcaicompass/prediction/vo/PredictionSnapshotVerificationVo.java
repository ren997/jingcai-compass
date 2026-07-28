package com.jingcaicompass.prediction.vo;

/** 已发布预测快照的当前存储校验结果。 */
public record PredictionSnapshotVerificationVo(
        Long snapshotId,
        String snapshotHash,
        Long contentLength,
        boolean verified
) {
}
