package com.jingcaicompass.snapshot.dto;

/**
 * 已完成规范化序列化的快照 manifest 内容。
 *
 * @param bytes 规范化 UTF-8 JSON 字节
 * @param sha256 manifest 字节 SHA-256
 * @param predictionCount 当前公开预测数量
 */
public record SnapshotManifestContentDto(
        byte[] bytes,
        String sha256,
        int predictionCount
) {

    public SnapshotManifestContentDto {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }
}
