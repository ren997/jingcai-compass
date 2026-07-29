package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PredictionLifecycleMonitoringServiceTest {

    private static final Instant DATABASE_TIME = Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void refreshesPersistentBacklogsFromDatabaseTimeAndActivatesEnabledTaskAlerts() {
        PredictionMapper predictionMapper = org.mockito.Mockito.mock(PredictionMapper.class);
        SettlementMapper settlementMapper = org.mockito.Mockito.mock(SettlementMapper.class);
        when(predictionMapper.selectDatabaseTime()).thenReturn(DATABASE_TIME);
        when(predictionMapper.countOverduePublishedPredictions(anyInstant())).thenReturn(2L);
        when(settlementMapper.countOverdueSettlementBacklog(anyInstant())).thenReturn(3L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            PredictionLifecycleMonitoringService service = new PredictionLifecycleMonitoringService(
                    predictionMapper,
                    settlementMapper,
                    new PredictionLifecycleMetrics(registry),
                    properties(),
                    taskProperties(true)
            );

            service.refresh();

            ArgumentCaptor<Instant> lockCutoff = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> settlementCutoff = ArgumentCaptor.forClass(Instant.class);
            verify(predictionMapper).countOverduePublishedPredictions(lockCutoff.capture());
            verify(settlementMapper).countOverdueSettlementBacklog(settlementCutoff.capture());
            assertThat(lockCutoff.getValue()).isEqualTo(DATABASE_TIME.minus(Duration.ofMinutes(1)));
            assertThat(settlementCutoff.getValue()).isEqualTo(DATABASE_TIME.minus(Duration.ofMinutes(10)));
            assertThat(registry.get("jingcai.prediction.lock.overdue").gauge().value()).isEqualTo(2.0);
            assertThat(registry.get("jingcai.settlement.backlog.predictions").gauge().value()).isEqualTo(3.0);
            assertThat(alert(registry, "prediction_lock", "overdue")).isEqualTo(1.0);
            assertThat(alert(registry, "settlement", "backlog_overdue")).isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }

    @Test
    void taskSwitchSuppressesAlertsButPreservesObservableBacklogCounts() {
        PredictionMapper predictionMapper = org.mockito.Mockito.mock(PredictionMapper.class);
        SettlementMapper settlementMapper = org.mockito.Mockito.mock(SettlementMapper.class);
        when(predictionMapper.selectDatabaseTime()).thenReturn(DATABASE_TIME);
        when(predictionMapper.countOverduePublishedPredictions(anyInstant())).thenReturn(1L);
        when(settlementMapper.countOverdueSettlementBacklog(anyInstant())).thenReturn(1L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            PredictionLifecycleMonitoringService service = new PredictionLifecycleMonitoringService(
                    predictionMapper,
                    settlementMapper,
                    new PredictionLifecycleMetrics(registry),
                    properties(),
                    taskProperties(false)
            );

            service.refresh();

            assertThat(registry.get("jingcai.prediction.lock.overdue").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("jingcai.settlement.backlog.predictions").gauge().value()).isEqualTo(1.0);
            assertThat(alert(registry, "prediction_lock", "overdue")).isZero();
            assertThat(alert(registry, "settlement", "backlog_overdue")).isZero();
        } finally {
            registry.close();
        }
    }

    private static Instant anyInstant() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }

    private static double alert(SimpleMeterRegistry registry, String component, String alert) {
        return registry.get("jingcai.lifecycle.alert.active")
                .tags("component", component, "alert", alert)
                .gauge()
                .value();
    }

    private static ObservabilityProperties properties() {
        return new ObservabilityProperties(
                true,
                Duration.ofMinutes(1),
                Duration.ofMinutes(30),
                Duration.ofMinutes(40),
                new BigDecimal("0.90"),
                3,
                20,
                Duration.ofMinutes(1),
                Duration.ofMinutes(10)
        );
    }

    private static SyncTaskProperties taskProperties(boolean enabled) {
        Duration delay = Duration.ofMinutes(1);
        return new SyncTaskProperties(
                enabled,
                new SyncTaskProperties.SportteryPoolTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.MatchResultTaskProperties(false, delay, Duration.ZERO, 7),
                new SyncTaskProperties.AsianOddsTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.DataPipelineTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.PredictionLockTaskProperties(enabled, Duration.ofSeconds(30), Duration.ZERO, 1),
                new SyncTaskProperties.SnapshotPublishTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.SettlementTaskProperties(enabled, Duration.ofMinutes(5), Duration.ZERO, 1)
        );
    }
}
