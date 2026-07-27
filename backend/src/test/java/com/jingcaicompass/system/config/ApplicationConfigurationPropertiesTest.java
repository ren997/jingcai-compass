package com.jingcaicompass.system.config;

import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.match.client.SportteryProviderType;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;
import com.jingcaicompass.snapshot.storage.SnapshotStorageProperties;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.sporttery.provider=china",
                    "app.sporttery.base-url=https://webapi.sporttery.cn",
                    "app.sporttery.connect-timeout=5s",
                    "app.sporttery.read-timeout=10s",
                    "app.sporttery.retry.max-attempts=2",
                    "app.sporttery.retry.delay=500ms",
                    "app.sporttery.quota-warning-threshold=0",
                    "app.asian-odds.provider=stub",
                    "app.asian-odds.base-url=https://api.the-odds-api.com",
                    "app.asian-odds.api-key=secret-key",
                    "app.asian-odds.connect-timeout=5s",
                    "app.asian-odds.read-timeout=10s",
                    "app.asian-odds.retry.max-attempts=2",
                    "app.asian-odds.retry.delay=500ms",
                    "app.asian-odds.quota-warning-threshold=0",
                    "app.pagination.max-page-size=100",
                    "app.snapshot.storage.type=local",
                    "app.snapshot.storage.path=./runtime/test-snapshots",
                    "app.tasks.enabled=false",
                    "app.tasks.sporttery-pool.enabled=false",
                    "app.tasks.sporttery-pool.fixed-delay=15m",
                    "app.tasks.sporttery-pool.initial-delay=30s",
                    "app.tasks.match-result.enabled=false",
                    "app.tasks.match-result.fixed-delay=15m",
                    "app.tasks.match-result.initial-delay=60s",
                    "app.tasks.match-result.lookback-days=7",
                    "app.tasks.asian-odds.enabled=false",
                    "app.tasks.asian-odds.fixed-delay=20m",
                    "app.tasks.asian-odds.initial-delay=45s",
                    "app.tasks.data-pipeline.enabled=false",
                    "app.tasks.data-pipeline.fixed-delay=20m",
                    "app.tasks.data-pipeline.initial-delay=45s",
                    "app.tasks.prediction-lock.enabled=false",
                    "app.tasks.prediction-lock.fixed-delay=30s",
                    "app.tasks.prediction-lock.initial-delay=15s",
                    "app.tasks.prediction-lock.batch-size=100",
                    "app.tasks.snapshot-publish.enabled=false",
                    "app.tasks.snapshot-publish.fixed-delay=5m",
                    "app.tasks.snapshot-publish.initial-delay=60s",
                    "app.tasks.settlement.enabled=false",
                    "app.tasks.settlement.fixed-delay=5m",
                    "app.tasks.settlement.initial-delay=75s",
                    "app.tasks.settlement.batch-size=100"
            );

    @Test
    void bindsTypedProviderAndTaskProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SportteryProviderProperties sporttery = context.getBean(SportteryProviderProperties.class);
            assertThat(sporttery.provider()).isEqualTo(SportteryProviderType.CHINA);
            assertThat(sporttery.baseUrl()).isEqualTo(URI.create("https://webapi.sporttery.cn"));
            assertThat(sporttery.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(sporttery.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(sporttery.retry().maxAttempts()).isEqualTo(2);
            assertThat(sporttery.retry().delay()).isEqualTo(Duration.ofMillis(500));

            AsianOddsProviderProperties asianOdds = context.getBean(AsianOddsProviderProperties.class);
            assertThat(asianOdds.provider()).isEqualTo(AsianOddsProviderTypeEnum.STUB);
            assertThat(asianOdds.baseUrl()).isEqualTo(URI.create("https://api.the-odds-api.com"));
            assertThat(asianOdds.apiKey()).isEqualTo("secret-key");
            assertThat(asianOdds.toString()).doesNotContain("secret-key");
            assertThat(asianOdds.toString()).contains("apiKey=***");

            SyncTaskProperties tasks = context.getBean(SyncTaskProperties.class);
            assertThat(tasks.enabled()).isFalse();
            assertThat(tasks.sportteryPool().enabled()).isFalse();
            assertThat(tasks.sportteryPool().fixedDelay()).isEqualTo(Duration.ofMinutes(15));
            assertThat(tasks.matchResult().enabled()).isFalse();
            assertThat(tasks.matchResult().fixedDelay()).isEqualTo(Duration.ofMinutes(15));
            assertThat(tasks.matchResult().initialDelay()).isEqualTo(Duration.ofSeconds(60));
            assertThat(tasks.matchResult().lookbackDays()).isEqualTo(7);
            assertThat(tasks.asianOdds().enabled()).isFalse();
            assertThat(tasks.asianOdds().fixedDelay()).isEqualTo(Duration.ofMinutes(20));
            assertThat(tasks.dataPipeline().enabled()).isFalse();
            assertThat(tasks.dataPipeline().fixedDelay()).isEqualTo(Duration.ofMinutes(20));
            assertThat(tasks.dataPipeline().initialDelay()).isEqualTo(Duration.ofSeconds(45));
            assertThat(tasks.predictionLock().enabled()).isFalse();
            assertThat(tasks.predictionLock().fixedDelay()).isEqualTo(Duration.ofSeconds(30));
            assertThat(tasks.predictionLock().initialDelay()).isEqualTo(Duration.ofSeconds(15));
            assertThat(tasks.predictionLock().batchSize()).isEqualTo(100);
            assertThat(tasks.snapshotPublish().enabled()).isFalse();
            assertThat(tasks.snapshotPublish().fixedDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(tasks.snapshotPublish().initialDelay()).isEqualTo(Duration.ofSeconds(60));
            assertThat(tasks.settlement().enabled()).isFalse();
            assertThat(tasks.settlement().fixedDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(tasks.settlement().initialDelay()).isEqualTo(Duration.ofSeconds(75));
            assertThat(tasks.settlement().batchSize()).isEqualTo(100);

            SnapshotStorageProperties storage =
                    context.getBean(SnapshotStorageProperties.class);
            assertThat(storage.type()).isEqualTo(SnapshotStorageTypeEnum.LOCAL);
            assertThat(storage.path().normalize())
                    .isEqualTo(Path.of("./runtime/test-snapshots").normalize());

            PaginationProperties pagination = context.getBean(PaginationProperties.class);
            assertThat(pagination.maxPageSize()).isEqualTo(100);
        });
    }

    @Test
    void rejectsConnectTimeoutBelowOneSecond() {
        contextRunner
                .withPropertyValues("app.sporttery.connect-timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.sporttery.connect-timeout");
                });
    }

    @Test
    void rejectsAsianOddsConnectTimeoutBelowOneSecond() {
        contextRunner
                .withPropertyValues("app.asian-odds.connect-timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.asian-odds.connect-timeout");
                });
    }

    @Test
    void rejectsRetryAttemptsBelowOne() {
        contextRunner
                .withPropertyValues("app.sporttery.retry.max-attempts=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.sporttery.retry.max-attempts");
                });
    }

    @Test
    void rejectsPageSizeBelowOne() {
        contextRunner
                .withPropertyValues("app.pagination.max-page-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.pagination.max-page-size");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sporttery-pool", "match-result", "asian-odds"})
    void rejectsPipelineCombinedWithIndividualTasks(String individualTask) {
        contextRunner
                .withPropertyValues(
                        "app.tasks.data-pipeline.enabled=true",
                        "app.tasks." + individualTask + ".enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.tasks.data-pipeline.enabled");
                });
    }

    @Test
    void rejectsPipelineDelayBelowOneSecond() {
        contextRunner
                .withPropertyValues("app.tasks.data-pipeline.fixed-delay=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.tasks.data-pipeline.fixed-delay");
                });
    }

    @Test
    void rejectsPredictionLockDelayBelowOneSecond() {
        contextRunner
                .withPropertyValues("app.tasks.prediction-lock.fixed-delay=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.tasks.prediction-lock.fixed-delay");
                });
    }

    @Test
    void rejectsPredictionLockBatchSizeOutsideRange() {
        contextRunner
                .withPropertyValues("app.tasks.prediction-lock.batch-size=1001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.tasks.prediction-lock.batch-size");
                });
    }

    @Test
    void allowsPredictionLockTogetherWithDataPipeline() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.data-pipeline.enabled=true",
                        "app.tasks.prediction-lock.enabled=true"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsSnapshotPublishDelayBelowOneSecond() {
        contextRunner
                .withPropertyValues("app.tasks.snapshot-publish.fixed-delay=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.tasks.snapshot-publish.fixed-delay");
                });
    }

    @Test
    void allowsSnapshotPublishTogetherWithOtherTasks() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.data-pipeline.enabled=true",
                        "app.tasks.prediction-lock.enabled=true",
                        "app.tasks.snapshot-publish.enabled=true"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsSettlementBatchSizeOutsideRange() {
        contextRunner
                .withPropertyValues("app.tasks.settlement.batch-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.tasks.settlement.batch-size");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            SportteryProviderProperties.class,
            AsianOddsProviderProperties.class,
            SyncTaskProperties.class,
            PaginationProperties.class,
            SnapshotStorageProperties.class
    })
    static class PropertiesConfiguration {
    }
}
