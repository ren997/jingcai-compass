package com.jingcaicompass.match.support;

import com.jingcaicompass.data.support.PayloadHashSupport;
import org.springframework.util.StringUtils;

/**
 * 为没有稳定联赛/球队外部 ID 的 Provider 生成可重放名称键。
 */
public final class ProviderEntityKeySupport {

    private static final String NAME_PREFIX = "NAME:";

    private ProviderEntityKeySupport() {
    }

    /**
     * 将 Provider 展示名称转换为不包含原文的稳定来源键。
     *
     * @param displayName Provider 原始展示名称
     * @return 以 {@code NAME:} 开头的 SHA-256 来源键
     */
    public static String nameKey(String displayName) {
        String normalized = NameNormalizationSupport.normalizedKey(displayName);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("displayName normalizes to empty key");
        }
        return NAME_PREFIX + PayloadHashSupport.sha256Hex(normalized);
    }
}
