package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MappingStatusEnum;

/** 以竞彩比赛为主体读取其外部映射候选的入参。 */
public record MappingReviewMatchDetailQueryDto(
        Long matchId,
        String providerCode,
        MappingStatusEnum mappingStatus
) {
}
