package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;

/** 供应商联赛或球队标准化复核分页筛选。 */
public record ProviderNormalizationReviewListQueryDto(
        ProviderNormalizationEntityTypeEnum entityType,
        String providerCode,
        MappingStatusEnum mappingStatus,
        Integer pageNo,
        Integer pageSize
) {
}
