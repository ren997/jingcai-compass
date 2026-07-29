package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.match.client.SportteryProviderType;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;

class DataSourceMonitoringServiceTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void faultInjectionRefreshesCoverageQuotaMappingAndFailureAlertStates() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("query".equals(method)) {
                String sql = invocation.getArgument(0, String.class);
                if (sql.contains("SELECT finished_at")) {
                    return List.of(Instant.now());
                }
                if (sql.contains("LIMIT ?")) {
                    return List.of("PARTIAL", "FAILED", "FAILED");
                }
                return List.of("SUCCESS");
            }
            if ("queryForObject".equals(method)) {
                String sql = invocation.getArgument(0, String.class);
                if (sql.contains("COUNT(DISTINCT odds.match_id)")) {
                    return 5;
                }
                if (sql.contains("match_source_mappings")) {
                    return 20;
                }
                if (sql.contains("SUM(quota_cost)")) {
                    return 50;
                }
                return 10;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        SportteryProvider sportteryProvider = org.mockito.Mockito.mock(SportteryProvider.class);
        AsianOddsProvider asianOddsProvider = org.mockito.Mockito.mock(AsianOddsProvider.class);
        when(sportteryProvider.providerCode()).thenReturn("CHINA_SPORTTERY");
        when(asianOddsProvider.providerCode()).thenReturn("THE_ODDS_API");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            DataSourceMonitoringService service = new DataSourceMonitoringService(
                    jdbcTemplate,
                    registry,
                    new ObservabilityProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(30),
                            Duration.ofMinutes(40), new java.math.BigDecimal("0.90"), 3, 20),
                    activeTasks(),
                    sportteryProvider,
                    asianOddsProvider,
                    sportteryProperties(),
                    asianOddsProperties()
            );

            service.refresh();

            assertThat(gauge(registry, "THE_ODDS_API", "coverage_low")).isEqualTo(1.0);
            assertThat(gauge(registry, "THE_ODDS_API", "mapping_backlog")).isEqualTo(1.0);
            assertThat(gauge(registry, "THE_ODDS_API", "quota_threshold_reached")).isEqualTo(1.0);
            assertThat(gauge(registry, "THE_ODDS_API", "sync_failure_streak")).isEqualTo(1.0);
            assertThat(registry.get("jingcai.datasource.asian_odds.coverage")
                    .tag("provider", "THE_ODDS_API").gauge().value()).isEqualTo(0.5);
        } finally {
            registry.close();
        }
    }

    private double gauge(SimpleMeterRegistry registry, String provider, String alert) {
        return registry.get("jingcai.datasource.alert.active")
                .tags("provider", provider, "alert", alert)
                .gauge()
                .value();
    }

    private SyncTaskProperties activeTasks() {
        Duration delay = Duration.ofMinutes(1);
        return new SyncTaskProperties(
                true,
                new SyncTaskProperties.SportteryPoolTaskProperties(true, delay, Duration.ZERO),
                new SyncTaskProperties.MatchResultTaskProperties(false, delay, Duration.ZERO, 7),
                new SyncTaskProperties.AsianOddsTaskProperties(true, delay, Duration.ZERO),
                new SyncTaskProperties.DataPipelineTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.PredictionLockTaskProperties(false, delay, Duration.ZERO, 1),
                new SyncTaskProperties.SnapshotPublishTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.SettlementTaskProperties(false, delay, Duration.ZERO, 1)
        );
    }

    private SportteryProviderProperties sportteryProperties() {
        return new SportteryProviderProperties(
                SportteryProviderType.CHINA,
                URI.create("https://sporttery.example.test"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new SportteryProviderProperties.RetryProperties(1, Duration.ofMillis(100)),
                0
        );
    }

    private AsianOddsProviderProperties asianOddsProperties() {
        return new AsianOddsProviderProperties(
                AsianOddsProviderTypeEnum.THE_ODDS_API,
                URI.create("https://odds.example.test"),
                "api-key-not-logged",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new AsianOddsProviderProperties.RetryProperties(1, Duration.ofMillis(100)),
                40
        );
    }
}
