package com.jingcaicompass.system.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.tasks")
public record SyncTaskProperties(
        boolean enabled,
        @Valid @NotNull SportteryPoolTaskProperties sportteryPool
) {

    public record SportteryPoolTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay
    ) {

        @AssertTrue(message = "app.tasks.sporttery-pool.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.sporttery-pool.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }
    }
}
