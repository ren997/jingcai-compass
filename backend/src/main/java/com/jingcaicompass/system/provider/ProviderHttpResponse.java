package com.jingcaicompass.system.provider;

import org.springframework.http.HttpHeaders;

/**
 * Provider HTTP 调用成功结果。
 */
public record ProviderHttpResponse(
        /** HTTP 状态码。 */
        int status,
        /** 响应体文本。 */
        String body,
        /** 额外重试次数（不含首次尝试）。 */
        int retryCount,
        /** 本轮估算额度消耗。 */
        int quotaCost,
        /** 响应头（可能为空）。 */
        HttpHeaders headers
) {
}
