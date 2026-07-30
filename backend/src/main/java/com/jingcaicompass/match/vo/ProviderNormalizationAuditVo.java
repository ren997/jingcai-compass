package com.jingcaicompass.match.vo;

import java.time.Instant;

/** 标准化映射的只读审计摘要。 */
public record ProviderNormalizationAuditVo(
        String operatorId,
        String actionType,
        String fieldName,
        Instant createdAt
) {
}
