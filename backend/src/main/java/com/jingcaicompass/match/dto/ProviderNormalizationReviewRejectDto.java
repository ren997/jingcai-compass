package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;

/** 拒绝一条待复核的供应商联赛或球队映射。 */
public record ProviderNormalizationReviewRejectDto(
        ProviderNormalizationEntityTypeEnum entityType,
        Long mappingId,
        String reason
) {
}
