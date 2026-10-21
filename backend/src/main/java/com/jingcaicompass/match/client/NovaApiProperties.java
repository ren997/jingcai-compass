package com.jingcaicompass.match.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * NovaAPI 连接与签名配置。
 *
 * <p>{@code privateKey} 是 Ed25519 私钥的 Base64（PKCS#8 或裸 32 字节种子均可），
 * 属于凭据，任何日志与异常消息都不得带出其值。</p>
 */
@Validated
@ConfigurationProperties("app.nova")
public record NovaApiProperties(
        @NotNull URI baseUrl,
        String apiKey,
        String privateKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull RetryProperties retry,
        @PositiveOrZero int quotaWarningThreshold
) {

    @AssertTrue(message = "app.nova.connect-timeout must be at least 1 second")
    public boolean isConnectTimeoutValid() {
        return connectTimeout != null && connectTimeout.compareTo(Duration.ofSeconds(1)) >= 0;
    }

    @AssertTrue(message = "app.nova.read-timeout must be at least 1 second")
    public boolean isReadTimeoutValid() {
        return readTimeout != null && readTimeout.compareTo(Duration.ofSeconds(1)) >= 0;
    }

    public record RetryProperties(
            @Min(1) int maxAttempts,
            @NotNull Duration delay
    ) {

        @AssertTrue(message = "app.nova.retry.delay must be at least 100 milliseconds")
        public boolean isDelayValid() {
            return delay != null && delay.compareTo(Duration.ofMillis(100)) >= 0;
        }
    }
}
