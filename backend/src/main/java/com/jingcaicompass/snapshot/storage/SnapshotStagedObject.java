package com.jingcaicompass.snapshot.storage;

/**
 * 已写入临时位置并通过内容校验的快照对象。
 *
 * @param objectKey 计划发布的相对对象键
 * @param stagingKey 存储实现内部临时标识
 * @param sha256 临时对象 SHA-256
 * @param contentLength 临时对象字节数
 */
public record SnapshotStagedObject(
        String objectKey,
        String stagingKey,
        String sha256,
        long contentLength
) {
}
