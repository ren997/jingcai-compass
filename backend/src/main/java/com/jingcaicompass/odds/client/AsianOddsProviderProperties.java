package com.jingcaicompass.odds.client;

import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties("app.asian-odds")
public record AsianOddsProviderProperties(
        @NotNull AsianOddsProviderTypeEnum provider,
        @NotNull URI baseUrl,
        String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull RetryProperties retry,
        @PositiveOrZero int quotaWarningThreshold,
        @Valid @NotNull TheOddsProperties theOdds
) {

    public AsianOddsProviderProperties(
            AsianOddsProviderTypeEnum provider,
            URI baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout,
            RetryProperties retry,
            int quotaWarningThreshold
    ) {
        this(provider, baseUrl, apiKey, connectTimeout, readTimeout, retry, quotaWarningThreshold,
                TheOddsProperties.defaults());
    }

    @ConstructorBinding
    public AsianOddsProviderProperties {
        theOdds = theOdds == null ? TheOddsProperties.defaults() : theOdds;
    }

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
                + ", theOdds=" + theOdds
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

    /** The Odds API 的非敏感查询范围与验证额度上限。 */
    public record TheOddsProperties(
            @NotNull Map<String, String> leagueSportKeys,
            @PositiveOrZero int quotaBudget
    ) {

        public TheOddsProperties {
            leagueSportKeys = leagueSportKeys == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(leagueSportKeys));
        }

        static TheOddsProperties defaults() {
            return new TheOddsProperties(Map.of(), 0);
        }
    }
}
