package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;

/** 在内部标准字典中搜索管理员可选择的确认目标。 */
public record ProviderNormalizationCandidateQueryDto(
        ProviderNormalizationEntityTypeEnum entityType,
        Long mappingId,
        String keyword
) {
}
