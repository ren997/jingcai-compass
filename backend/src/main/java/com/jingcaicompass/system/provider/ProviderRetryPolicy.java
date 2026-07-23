package com.jingcaicompass.system.provider;

import java.time.Duration;
import java.util.Objects;

/**
 * Provider HTTP 重试策略。
 */
public record ProviderRetryPolicy(
        /** 最大尝试次数（含首次）。 */
        int maxAttempts,
        /** 默认退避间隔；429 优先使用 Retry-After。 */
        Duration delay
) {

    public ProviderRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
    }

    /** 是否可对给定 HTTP 状态重试。 */
    public boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /** 是否为不可重试的客户端错误。 */
    public boolean isNonRetryableClientError(int status) {
        return status >= 400 && status < 500 && status != 429;
    }
}
