package com.jingcaicompass.system.provider;

/**
 * Provider 调用失败的统一错误分类。
 */
public enum ProviderErrorCategory {
    /** 请求参数不合法。 */
    INVALID_PARAMETER,
    /** 上游额度或限流耗尽。 */
    QUOTA_EXCEEDED,
    /** 上游故障、超时或不可用。 */
    UPSTREAM_FAILURE,
    /** 响应结构无法解析。 */
    PARSE_FAILURE
}
