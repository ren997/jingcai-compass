package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;

/** 人工确认一条供应商联赛或球队映射。 */
public record ProviderNormalizationReviewConfirmDto(
        ProviderNormalizationEntityTypeEnum entityType,
        Long mappingId,
        Long targetEntityId
) {
}
