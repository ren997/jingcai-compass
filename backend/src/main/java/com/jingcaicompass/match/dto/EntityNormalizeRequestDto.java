package com.jingcaicompass.match.dto;

/**
 * 联赛/球队标准化入参。
 *
 * @param providerCode 供应商编码，可空
 * @param externalId 供应商外部 ID，可空；非空时优先按已确认映射解析
 * @param displayName 原始展示名，用于规范化匹配与新建实体展示字段
 */
public record EntityNormalizeRequestDto(
        String providerCode,
        String externalId,
        String displayName
) {
}
