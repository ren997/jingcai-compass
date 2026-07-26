package com.jingcaicompass.match.dto;

/**
 * 重新打开已拒绝映射。
 *
 * @param mappingId 映射行 ID
 */
public record MappingReviewReopenDto(
        Long mappingId
) {
}
