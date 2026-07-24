package com.jingcaicompass.match.dto;

/**
 * 人工拒绝映射。
 *
 * @param mappingId 映射行 ID
 * @param reason 拒绝原因，可空
 * @param operatorId 操作者
 */
public record MappingReviewRejectDto(
        Long mappingId,
        String reason,
        String operatorId
) {
}
