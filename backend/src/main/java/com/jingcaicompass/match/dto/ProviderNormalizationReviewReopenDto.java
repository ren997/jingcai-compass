package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;

/** 将已拒绝的供应商联赛或球队映射重新打开。 */
public record ProviderNormalizationReviewReopenDto(
        ProviderNormalizationEntityTypeEnum entityType,
        Long mappingId
) {
}
