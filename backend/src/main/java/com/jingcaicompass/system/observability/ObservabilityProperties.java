package com.jingcaicompass.system.observability;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 数据源观测与告警条件的可配置阈值。 */
@Validated
@ConfigurationProperties("app.observability")
public record ObservabilityProperties(
        boolean enabled,
        @NotNull Duration refreshDelay,
        @NotNull Duration sportteryMaxSyncAge,
        @NotNull Duration asianOddsMaxSyncAge,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal coverageMinimumRate,
        @Min(1) int failedRunStreakThreshold,
        @Min(1) int pendingMappingThreshold,
        @NotNull Duration predictionLockOverdueGrace,
        @NotNull Duration settlementBacklogGrace
) {
}
