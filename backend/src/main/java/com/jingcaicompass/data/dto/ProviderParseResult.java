package com.jingcaicompass.data.dto;

/**
 * 领域解析回调结果。
 */
public record ProviderParseResult(
        /** 解析成功条数。 */
        int successCount,
        /** 解析失败条数。 */
        int failureCount,
        /** 错误摘要，可空。 */
        String errorMessage
) {

    public static ProviderParseResult empty() {
        return new ProviderParseResult(0, 0, null);
    }
}
