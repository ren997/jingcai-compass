package com.jingcaicompass.snapshot.dto;

import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 单次公开预测快照发布或复用结果。
 *
 * @param snapshotId 快照元数据 ID
 * @param snapshotDate 竞彩业务日
 * @param snapshotVersion 同业务日快照版本
 * @param snapshotStatus 发布生命周期状态
 * @param snapshotHash manifest 字节 SHA-256
 * @param predictionCount 当前公开预测数量
 * @param storageType 存储实现编码
 * @param objectKey 不可覆盖对象键
 * @param objectVersion 可选对象存储版本
 * @param fileUrl 已发布文件位置
 * @param contentType 文件内容类型
 * @param contentLength 文件字节数
 * @param publishedAt 数据库记录的成功发布时间
 * @param failureReason 失败摘要
 * @param reused 是否复用已有成功快照
 */
public record PredictionSnapshotResultDto(
        Long snapshotId,
        LocalDate snapshotDate,
        Integer snapshotVersion,
        PredictionSnapshotStatusEnum snapshotStatus,
        String snapshotHash,
        int predictionCount,
        String storageType,
        String objectKey,
        String objectVersion,
        String fileUrl,
        String contentType,
        Long contentLength,
        Instant publishedAt,
        String failureReason,
        boolean reused
) {
}
