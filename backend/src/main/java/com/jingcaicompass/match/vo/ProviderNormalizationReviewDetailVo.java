package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 供应商联赛或球队标准化复核详情。 */
public record ProviderNormalizationReviewDetailVo(
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
        List<ProviderNormalizationAuditVo> auditHistory,
        Instant updatedAt
) {
    public ProviderNormalizationReviewDetailVo {
        auditHistory = auditHistory == null ? List.of() : List.copyOf(auditHistory);
    }
}
