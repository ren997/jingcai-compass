package com.jingcaicompass.odds.client;

import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("app.asian-odds")
public record AsianOddsProviderProperties(
        @NotNull AsianOddsProviderTypeEnum provider,
        @NotNull URI baseUrl,
        String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull RetryProperties retry,
        @PositiveOrZero int quotaWarningThreshold
) {

    @AssertTrue(message = "app.asian-odds.connect-timeout must be at least 1 second")
    public boolean isConnectTimeoutValid() {
        return connectTimeout != null && connectTimeout.compareTo(Duration.ofSeconds(1)) >= 0;
    }

    @AssertTrue(message = "app.asian-odds.read-timeout must be at least 1 second")
    public boolean isReadTimeoutValid() {
        return readTimeout != null && readTimeout.compareTo(Duration.ofSeconds(1)) >= 0;
    }

    @Override
    public String toString() {
        return "AsianOddsProviderProperties["
                + "provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "" : "***")
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", retry=" + retry
                + ", quotaWarningThreshold=" + quotaWarningThreshold
                + "]";
    }

    public record RetryProperties(
            @Min(1) int maxAttempts,
            @NotNull Duration delay
    ) {

        @AssertTrue(message = "app.asian-odds.retry.delay must be at least 100 milliseconds")
        public boolean isDelayValid() {
            return delay != null && delay.compareTo(Duration.ofMillis(100)) >= 0;
        }
    }
}
