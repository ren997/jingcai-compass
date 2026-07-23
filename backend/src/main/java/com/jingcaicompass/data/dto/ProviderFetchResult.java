package com.jingcaicompass.data.dto;

import java.time.Instant;

/**
 * Provider 拉取回调结果。
 */
public record ProviderFetchResult(
        /** 请求幂等键。 */
        String requestKey,
        /** 原始 JSON 文本。 */
        String payloadJson,
        /** HTTP 状态码，可空。 */
        Integer httpStatus,
        /** 供应商侧更新时间，可空。 */
        Instant providerUpdatedAt,
        /** 本轮重试次数。 */
        int retryCount,
        /** 本轮额度消耗。 */
        int quotaCost
) {
}
