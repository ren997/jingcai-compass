package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;

/**
 * 联赛/球队标准化出参。
 *
 * @param entityId 标准实体 ID
 * @param outcome 解析结果类型
 * @param mappingStatus 关联映射状态；无映射时为 null
 * @param method 解析方法：EXTERNAL_ID / ALIAS / EXACT_NAME / NAME_CANDIDATE
 */
public record EntityNormalizeResultDto(
        Long entityId,
        EntityNormalizeOutcomeEnum outcome,
        MappingStatusEnum mappingStatus,
        String method
) {
}
