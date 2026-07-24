package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.LeagueAlias;

/** 联赛标准化：外部 ID → 已确认别名 → 精确标准名；未知只建 PENDING 候选。 */
public interface LeagueNormalizationService {

    /** 解析或创建标准联赛。 */
    EntityNormalizeResultDto resolve(EntityNormalizeRequestDto request);

    /**
     * 确认并写入联赛别名；normalized UNIQUE 冲突时拒绝。
     *
     * @param leagueId 标准联赛 ID
     * @param aliasRaw 原始别名
     * @param source 来源
     * @param confirmedBy 确认人
     */
    LeagueAlias confirmAlias(Long leagueId, String aliasRaw, String source, String confirmedBy);
}
