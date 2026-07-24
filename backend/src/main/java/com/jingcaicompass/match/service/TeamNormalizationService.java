package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.TeamAlias;

/** 球队标准化：外部 ID → 已确认别名 → 精确标准名；未知只建 PENDING 候选。 */
public interface TeamNormalizationService {

    /** 解析或创建标准球队。 */
    EntityNormalizeResultDto resolve(EntityNormalizeRequestDto request);

    /**
     * 确认并写入球队别名；normalized UNIQUE 冲突时拒绝。
     *
     * @param teamId 标准球队 ID
     * @param aliasRaw 原始别名
     * @param source 来源
     * @param confirmedBy 确认人
     */
    TeamAlias confirmAlias(Long teamId, String aliasRaw, String source, String confirmedBy);
}
