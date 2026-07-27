package com.jingcaicompass.settlement.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.settlement.dto.SettlementBatchResultDto;
import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 自动结算 Job 默认关闭，并把配置批次原样委派给服务。 */
class SettlementJobTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(SettlementService.class, () -> mock(SettlementService.class))
            .withBean(SyncTaskProperties.class, SettlementJobTest::taskProperties)
            .withUserConfiguration(SettlementJob.class);

    @Test
    void createsJobOnlyWhenGlobalAndSettlementSwitchesAreEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.settlement.enabled=true"
                )
                .run(context -> assertThat(context).hasSingleBean(SettlementJob.class));
    }

    @Test
    void settlementSwitchDefaultsToDisabled() {
        contextRunner
                .withPropertyValues("app.tasks.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(SettlementJob.class));
    }

    @Test
    void globalSwitchDisablesSettlementJob() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=false",
                        "app.tasks.settlement.enabled=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(SettlementJob.class));
    }

    @Test
    void delegatesConfiguredBatchAndKeepsResultAvailableForLogging() {
        SettlementService service = mock(SettlementService.class);
        when(service.settlePendingPredictions(7)).thenReturn(new SettlementBatchResultDto(1, 1, 2, 0, 0, 0));
        SettlementJob job = new SettlementJob(service, taskProperties());

        job.settlePendingPredictions();

        verify(service).settlePendingPredictions(7);
    }

    private static SyncTaskProperties taskProperties() {
        return new SyncTaskProperties(
                false,
                new SyncTaskProperties.SportteryPoolTaskProperties(false, Duration.ofMinutes(15), Duration.ofSeconds(30)),
                new SyncTaskProperties.MatchResultTaskProperties(false, Duration.ofMinutes(15), Duration.ofSeconds(60), 7),
                new SyncTaskProperties.AsianOddsTaskProperties(false, Duration.ofMinutes(20), Duration.ofSeconds(45)),
                new SyncTaskProperties.DataPipelineTaskProperties(false, Duration.ofMinutes(20), Duration.ofSeconds(45)),
                new SyncTaskProperties.PredictionLockTaskProperties(false, Duration.ofSeconds(30), Duration.ofSeconds(15), 100),
                new SyncTaskProperties.SnapshotPublishTaskProperties(false, Duration.ofMinutes(5), Duration.ofSeconds(60)),
                new SyncTaskProperties.SettlementTaskProperties(false, Duration.ofMinutes(5), Duration.ofSeconds(75), 7)
        );
    }
}
