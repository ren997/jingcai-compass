package com.jingcaicompass.match.dto;

/**
 * 人工确认映射。
 *
 * @param mappingId 映射行 ID
 * @param targetMatchId 可选；指定内部比赛，空则保留当前 matchId
 * @param operatorId 操作者
 */
public record MappingReviewConfirmDto(
        Long mappingId,
        Long targetMatchId,
        String operatorId
) {
}
