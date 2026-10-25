package com.jingcaicompass.system.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.tasks")
public record SyncTaskProperties(
        boolean enabled,
        @Valid @NotNull SportteryPoolTaskProperties sportteryPool,
        @Valid @NotNull MatchResultTaskProperties matchResult,
        @Valid @NotNull AsianOddsTaskProperties asianOdds,
        @Valid @NotNull DataPipelineTaskProperties dataPipeline,
        @Valid @NotNull PredictionLockTaskProperties predictionLock,
        @Valid @NotNull SnapshotPublishTaskProperties snapshotPublish,
        @Valid @NotNull SettlementTaskProperties settlement,
        @Valid @NotNull PredictionAutoPublishTaskProperties predictionAutoPublish
) {

    @AssertTrue(message = "app.tasks.data-pipeline.enabled cannot be combined with individual sync tasks")
    public boolean isDataPipelineExclusive() {
        return dataPipeline == null
                || !dataPipeline.enabled()
                || (sportteryPool != null
                && matchResult != null
                && asianOdds != null
                && !sportteryPool.enabled()
                && !matchResult.enabled()
                && !asianOdds.enabled());
    }

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

    public record MatchResultTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay,
            @Min(1) @Max(365) int lookbackDays
    ) {

        @AssertTrue(message = "app.tasks.match-result.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.match-result.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }
    }

    public record AsianOddsTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay
    ) {

        @AssertTrue(message = "app.tasks.asian-odds.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.asian-odds.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }
    }

    public record DataPipelineTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay
    ) {

        @AssertTrue(message = "app.tasks.data-pipeline.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.data-pipeline.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }
    }

    public record PredictionLockTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay,
            @Min(1) @Max(1000) int batchSize
    ) {

        @AssertTrue(message = "app.tasks.prediction-lock.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.prediction-lock.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }
    }

    public record SnapshotPublishTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay
    ) {

        @AssertTrue(message = "app.tasks.snapshot-publish.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.snapshot-publish.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }
    }

    public record SettlementTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay,
            @Min(1) @Max(1000) int batchSize
    ) {

        @AssertTrue(message = "app.tasks.settlement.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.settlement.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }
    }

    /**
     * 自动发布任务配置。
     *
     * @param regenerateInterval 两次重新生成之间的最小间隔；{@code 0} 表示不节流（每轮都生成）
     * @param operator 自动发布写入审计日志的操作者标识，不能用空值——发布服务会拒绝空操作者
     */
    public record PredictionAutoPublishTaskProperties(
            boolean enabled,
            @NotNull Duration fixedDelay,
            @NotNull Duration initialDelay,
            @NotNull Duration regenerateInterval,
            @NotBlank String operator
    ) {

        @AssertTrue(message = "app.tasks.prediction-auto-publish.fixed-delay must be at least 1 second")
        public boolean isFixedDelayValid() {
            return fixedDelay != null && fixedDelay.compareTo(Duration.ofSeconds(1)) >= 0;
        }

        @AssertTrue(message = "app.tasks.prediction-auto-publish.initial-delay must not be negative")
        public boolean isInitialDelayValid() {
            return initialDelay != null && !initialDelay.isNegative();
        }

        @AssertTrue(message = "app.tasks.prediction-auto-publish.regenerate-interval must not be negative")
        public boolean isRegenerateIntervalValid() {
            return regenerateInterval != null && !regenerateInterval.isNegative();
        }
    }
}
