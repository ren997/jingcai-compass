package com.jingcaicompass.prediction.vo;

import java.time.Instant;
import java.time.LocalDate;

/** 可公开下载和校验的已验证预测快照元数据。 */
public record PredictionSnapshotVo(
        Long snapshotId,
        LocalDate snapshotDate,
        Integer snapshotVersion,
        String snapshotHash,
        String contentType,
        Long contentLength,
        Instant publishedAt
) {
}
