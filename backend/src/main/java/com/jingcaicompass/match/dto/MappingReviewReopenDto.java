package com.jingcaicompass.match.dto;

/**
 * 重新打开已拒绝映射。
 *
 * @param mappingId 映射行 ID
 * @param operatorId 操作者
 */
public record MappingReviewReopenDto(
        Long mappingId,
        String operatorId
) {
}
