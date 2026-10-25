package com.jingcaicompass.system.config;

import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.match.client.SportteryProviderType;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.client.AsianModelProperties;
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

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.sporttery.provider=nova",
                    "app.sporttery.quota-warning-threshold=0",
                    "app.asian-odds.provider=stub",
                    "app.asian-odds.quota-warning-threshold=0",
                    "app.asian-odds.aggregation.bookmaker-whitelist=pinnacle,matchbook",
                    "app.asian-odds.aggregation.min-bookmakers=2",
                    "app.asian-odds.aggregation.min-whitelist-bookmakers=2",
                    "app.asian-model.neighbor-tolerance=0.0350",
                    "app.asian-model.residual-tolerance=0.0005",
                    "app.asian-model.max-goals=28",
                    "app.asian-model.dispersion-tolerance=0.0250",
                    "app.asian-model.safety-margin=0.0050",
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
                    "app.tasks.settlement.batch-size=100",
                    "app.tasks.prediction-auto-publish.enabled=false",
                    "app.tasks.prediction-auto-publish.fixed-delay=15m",
                    "app.tasks.prediction-auto-publish.initial-delay=2m",
                    "app.tasks.prediction-auto-publish.regenerate-interval=3h",
                    "app.tasks.prediction-auto-publish.operator=system:auto-publish"
            );

    @Test
    void bindsTypedProviderAndTaskProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SportteryProviderProperties sporttery = context.getBean(SportteryProviderProperties.class);
            assertThat(sporttery.provider()).isEqualTo(SportteryProviderType.NOVA);
            assertThat(sporttery.quotaWarningThreshold()).isZero();

            AsianOddsProviderProperties asianOdds = context.getBean(AsianOddsProviderProperties.class);
            assertThat(asianOdds.provider()).isEqualTo(AsianOddsProviderTypeEnum.STUB);
            assertThat(asianOdds.quotaWarningThreshold()).isZero();

            AsianOddsAggregationProperties aggregation =
                    context.getBean(AsianOddsAggregationProperties.class);
            assertThat(aggregation.bookmakerWhitelist()).containsExactly("pinnacle", "matchbook");
            assertThat(aggregation.normalizedWhitelist()).containsExactly("pinnacle", "matchbook");
            assertThat(aggregation.minBookmakers()).isEqualTo(2);
            assertThat(aggregation.minWhitelistBookmakers()).isEqualTo(2);

            AsianModelProperties asianModel = context.getBean(AsianModelProperties.class);
            assertThat(asianModel.neighborTolerance()).isEqualByComparingTo("0.0350");
            assertThat(asianModel.residualTolerance()).isEqualByComparingTo("0.0005");
            assertThat(asianModel.maxGoals()).isEqualTo(28);
            assertThat(asianModel.dispersionTolerance()).isEqualByComparingTo("0.0250");
            assertThat(asianModel.safetyMargin()).isEqualByComparingTo("0.0050");

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
            assertThat(tasks.predictionAutoPublish().enabled()).isFalse();
            assertThat(tasks.predictionAutoPublish().fixedDelay()).isEqualTo(Duration.ofMinutes(15));
            assertThat(tasks.predictionAutoPublish().regenerateInterval()).isEqualTo(Duration.ofHours(3));
            assertThat(tasks.predictionAutoPublish().operator()).isEqualTo("system:auto-publish");

            SyncTaskSchedule schedule = new SyncTaskSchedule(tasks);
            assertThat(schedule.sportteryPoolFixedDelayMillis()).isEqualTo(900_000L);
            assertThat(schedule.sportteryPoolInitialDelayMillis()).isEqualTo(30_000L);
            assertThat(schedule.matchResultInitialDelayMillis()).isEqualTo(60_000L);
            assertThat(schedule.asianOddsFixedDelayMillis()).isEqualTo(1_200_000L);
            assertThat(schedule.asianOddsInitialDelayMillis()).isEqualTo(45_000L);
            assertThat(schedule.dataPipelineInitialDelayMillis()).isEqualTo(45_000L);
            assertThat(schedule.predictionLockInitialDelayMillis()).isEqualTo(15_000L);
            assertThat(schedule.snapshotPublishInitialDelayMillis()).isEqualTo(60_000L);
            assertThat(schedule.settlementInitialDelayMillis()).isEqualTo(75_000L);

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
    void rejectsAutoPublishRegenerateIntervalBelowZero() {
        contextRunner
                .withPropertyValues("app.tasks.prediction-auto-publish.regenerate-interval=-1s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.tasks.prediction-auto-publish.regenerate-interval");
                });
    }

    @Test
    void rejectsAutoPublishBlankOperator() {
        // 空操作者会让发布服务对每一条候选都抛 AUTH_UNAUTHORIZED，
        // 表现是「自动发布每轮全失败」，比启动期报错难查得多
        contextRunner
                .withPropertyValues("app.tasks.prediction-auto-publish.operator= ")
                .run(context -> assertThat(context).hasFailed());
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

    @Test
    void rejectsAsianOddsAggregationMinBookmakersBelowTwo() {
        contextRunner
                .withPropertyValues("app.asian-odds.aggregation.min-bookmakers=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.asian-odds.aggregation.min-bookmakers");
                });
    }

    @Test
    void appliesAggregationDefaultsWhenSectionIsAbsent() {
        // 未配置 app.asian-odds.aggregation 时，构造绑定会以 null 实例化 record。
        AsianOddsAggregationProperties defaults =
                new AsianOddsAggregationProperties(null, null, null);

        assertThat(defaults.bookmakerWhitelist())
                .containsExactly("pinnacle", "matchbook", "betfair");
        assertThat(defaults.minBookmakers()).isEqualTo(2);
        assertThat(defaults.minWhitelistBookmakers()).isEqualTo(2);
        assertThat(defaults.normalizedWhitelist()).containsExactly("pinnacle", "matchbook", "betfair");
    }

    @Test
    void rejectsAsianModelMaxGoalsOutsideRange() {
        contextRunner
                .withPropertyValues("app.asian-model.max-goals=19")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.asian-model.max-goals");
                });
    }

    @Test
    void rejectsAsianModelDispersionToleranceNotPositive() {
        // 0 会让「σ > 阈值」永远成立，等于整场拒发，必须当成配置错误而不是合法取值。
        contextRunner
                .withPropertyValues("app.asian-model.dispersion-tolerance=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.asian-model.dispersion-tolerance");
                });
    }

    @Test
    void rejectsAsianModelSafetyMarginAboveOne() {
        contextRunner
                .withPropertyValues("app.asian-model.safety-margin=1.5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.asian-model.safety-margin");
                });
    }

    @Test
    void appliesAsianModelDefaultsWhenSectionIsAbsent() {
        // 未配置 app.asian-model 时构造绑定会以 null 实例化 record，须回落到实测标定的默认值。
        AsianModelProperties defaults = new AsianModelProperties(null, null, null, null, null);

        assertThat(defaults.neighborTolerance()).isEqualByComparingTo("0.0400");
        assertThat(defaults.residualTolerance()).isEqualByComparingTo("0.0010");
        assertThat(defaults.maxGoals()).isEqualTo(24);
        assertThat(defaults.dispersionTolerance()).isEqualByComparingTo("0.0200");
        assertThat(defaults.safetyMargin()).isEqualByComparingTo("0.0100");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            SportteryProviderProperties.class,
            AsianOddsProviderProperties.class,
            AsianOddsAggregationProperties.class,
            AsianModelProperties.class,
            SyncTaskProperties.class,
            PaginationProperties.class,
            SnapshotStorageProperties.class
    })
    static class PropertiesConfiguration {
    }
}
