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
 *
 * <p>{@code historyReadTimeout} 单独一项而不是复用 {@code readTimeout}：历史接口
 * {@code /nova/tf/odds-history} 要把一场比赛的全部变快点一次性返回，实测老场次响应可达
 * <b>20.8s</b>、388KB，正好卡在 {@code read-timeout: 20s} 上——不是偶发慢，而是越老的场次越慢。
 * 用同一份超时会出现「近月能采、远月必超时」这种看起来像数据缺失的假象。
 * 为空时回退到 {@code readTimeout}，保持既有部署不配也能启动。</p>
 */
@Validated
@ConfigurationProperties("app.nova")
public record NovaApiProperties(
        @NotNull URI baseUrl,
        String apiKey,
        String privateKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        Duration historyReadTimeout,
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

    @AssertTrue(message = "app.nova.history-read-timeout must be at least 1 second when configured")
    public boolean isHistoryReadTimeoutValid() {
        return historyReadTimeout == null || historyReadTimeout.compareTo(Duration.ofSeconds(1)) >= 0;
    }

    /** 历史接口实际使用的读超时：未单独配置时沿用 {@code readTimeout}。 */
    public Duration effectiveHistoryReadTimeout() {
        return historyReadTimeout == null ? readTimeout : historyReadTimeout;
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
