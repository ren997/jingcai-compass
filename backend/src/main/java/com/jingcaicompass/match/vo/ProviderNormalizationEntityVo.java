package com.jingcaicompass.match.vo;

/** 管理员复核时展示的内部标准联赛或球队摘要。 */
public record ProviderNormalizationEntityVo(
        Long entityId,
        String nameZh,
        String nameEn
) {
}
