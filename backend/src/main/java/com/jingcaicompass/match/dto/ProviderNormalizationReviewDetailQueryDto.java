package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;

/** 查询一条供应商标准化复核详情。 */
public record ProviderNormalizationReviewDetailQueryDto(
        ProviderNormalizationEntityTypeEnum entityType,
        Long mappingId
) {
}
