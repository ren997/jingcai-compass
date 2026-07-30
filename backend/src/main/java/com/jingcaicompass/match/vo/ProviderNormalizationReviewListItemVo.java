package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;
import java.math.BigDecimal;
import java.time.Instant;

/** 供应商联赛或球队标准化复核列表项。 */
public record ProviderNormalizationReviewListItemVo(
        Long mappingId,
        ProviderNormalizationEntityTypeEnum entityType,
        String providerCode,
        String externalId,
        String externalScope,
        String externalDisplayName,
        String externalNormalizedKey,
        MappingStatusEnum mappingStatus,
        BigDecimal mappingConfidence,
        String mappingMethod,
        ProviderNormalizationEntityVo currentEntity,
        Instant updatedAt
) {
}
