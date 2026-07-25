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

    public static String nameKey(String displayName) {
        String normalized = NameNormalizationSupport.normalizedKey(displayName);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("displayName normalizes to empty key");
        }
        return NAME_PREFIX + PayloadHashSupport.sha256Hex(normalized);
    }
}
