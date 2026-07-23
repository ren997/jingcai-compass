package com.jingcaicompass.system.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Provider HTTP GET 请求描述。
 */
public record ProviderHttpRequest(
        /** 相对 path 或完整 URI 路径（含 query）。 */
        String path,
        /** 额外请求头；不得依赖此对象打印敏感值。 */
        Map<String, String> headers
) {

    public ProviderHttpRequest(String path) {
        this(path, Map.of());
    }

    public ProviderHttpRequest {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        headers = headers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public static ProviderHttpRequest of(String path) {
        return new ProviderHttpRequest(path);
    }

    public static ProviderHttpRequest of(String path, Map<String, String> headers) {
        return new ProviderHttpRequest(path, Objects.requireNonNullElse(headers, Map.of()));
    }
}
