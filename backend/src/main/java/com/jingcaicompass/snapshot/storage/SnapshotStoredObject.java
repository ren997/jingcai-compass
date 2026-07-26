package com.jingcaicompass.snapshot.storage;

import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;

/**
 * 已原子发布的不可覆盖快照对象元数据。
 *
 * @param storageType 存储实现类型
 * @param objectKey 最终对象键
 * @param objectVersion 可选对象存储版本
 * @param fileUrl 已发布文件位置
 * @param contentType 文件内容类型
 * @param contentLength 文件字节数
 * @param sha256 最终对象 SHA-256
 */
public record SnapshotStoredObject(
        SnapshotStorageTypeEnum storageType,
        String objectKey,
        String objectVersion,
        String fileUrl,
        String contentType,
        long contentLength,
        String sha256
) {
}
