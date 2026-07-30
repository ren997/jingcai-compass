package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MappingNormalizationRoleEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;

/** 赛事复核页中一条可选的联赛或球队标准化确认建议。 */
public record MappingReviewNormalizationProposalVo(
        Long sourceMappingId,
        MappingNormalizationRoleEnum role,
        Long providerMappingId,
        String externalDisplayName,
        Long targetEntityId,
        String targetEntityName,
        MappingStatusEnum mappingStatus,
        boolean selectable,
        String unavailableReason
) {
}
