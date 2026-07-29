package com.jingcaicompass.system.observability;

import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 从持久化同步、比赛、盘口和映射事实刷新数据源 Gauge 与告警状态。
 *
 * <p>仅使用 Provider、数据类型和固定告警名称作为标签；比赛和运行 ID 仅保留在结构化日志中。</p>
 */
@Component
@ConditionalOnBean(DataSource.class)
public class DataSourceMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceMonitoringService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final ObservabilityProperties properties;
    private final SyncTaskProperties taskProperties;
    private final SportteryProvider sportteryProvider;
    private final AsianOddsProvider asianOddsProvider;
    private final SportteryProviderProperties sportteryProperties;
    private final AsianOddsProviderProperties asianOddsProperties;
    private final Instant startedAt = Instant.now();
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    public DataSourceMonitoringService(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            ObservabilityProperties properties,
            SyncTaskProperties taskProperties,
            SportteryProvider sportteryProvider,
            AsianOddsProvider asianOddsProvider,
            SportteryProviderProperties sportteryProperties,
            AsianOddsProviderProperties asianOddsProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.taskProperties = taskProperties;
        this.sportteryProvider = sportteryProvider;
        this.asianOddsProvider = asianOddsProvider;
        this.sportteryProperties = sportteryProperties;
        this.asianOddsProperties = asianOddsProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${app.observability.refresh-delay:1m}")
    public void refreshScheduled() {
        refresh();
    }

    /** 刷新当前上海业务日的数据源事实指标；查询失败不输出异常文本。 */
    public void refresh() {
        if (!properties.enabled()) {
            return;
        }
        try {
            LocalDate businessDate = LocalDate.now(SHANGHAI);
            refreshSporttery(businessDate);
            refreshAsianOdds(businessDate);
        } catch (RuntimeException exception) {
            log.error("event=datasource_monitor_failed exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    private void refreshSporttery(LocalDate businessDate) {
        String provider = sportteryProvider.providerCode();
        boolean active = isSportteryActive();
        SyncState state = readSyncState(provider, ProviderDataTypeEnum.SPORTTERY_POOL);
        double age = ageSeconds(state.lastSuccessAt());
        int matchCount = queryInt("SELECT COUNT(*) FROM matches WHERE lottery_date = ?", businessDate);

        gauge("jingcai.datasource.pool.matches", matchCount, "provider", provider);
        recordSyncState(provider, ProviderDataTypeEnum.SPORTTERY_POOL, state, age, properties.sportteryMaxSyncAge());
        alert(provider, "sync_stale", active && stale(age, properties.sportteryMaxSyncAge()));
        boolean successfulPoolSeen = "SUCCESS".equals(state.latestStatus()) && !stale(age, properties.sportteryMaxSyncAge());
        alert(provider, "pool_empty", active && successfulPoolSeen && matchCount == 0);
        alert(provider, "sync_failure_streak", active && state.failedStreak() >= properties.failedRunStreakThreshold());
        recordQuota(provider, ProviderDataTypeEnum.SPORTTERY_POOL, businessDate, sportteryProperties.quotaWarningThreshold(), active);
    }

    private void refreshAsianOdds(LocalDate businessDate) {
        String provider = asianOddsProvider.providerCode();
        boolean active = isAsianOddsActive();
        SyncState state = readSyncState(provider, ProviderDataTypeEnum.ASIAN_ODDS);
        double age = ageSeconds(state.lastSuccessAt());
        int poolCount = queryInt("SELECT COUNT(*) FROM matches WHERE lottery_date = ?", businessDate);
        int coveredCount = queryInt("""
                SELECT COUNT(DISTINCT odds.match_id)
                FROM asian_odds_snapshots odds
                JOIN matches match ON match.id = odds.match_id
                WHERE match.lottery_date = ?
                  AND odds.provider_code = ?
                """, businessDate, provider);
        double coverage = poolCount == 0 ? 0.0 : (double) coveredCount / poolCount;
        int pendingMappings = queryInt("""
                SELECT COUNT(*)
                FROM match_source_mappings
                WHERE provider_code = ?
                  AND mapping_status = 'PENDING'
                """, provider);

        gauge("jingcai.datasource.asian_odds.coverage", coverage, "provider", provider);
        gauge("jingcai.mapping.pending", pendingMappings, "provider", provider);
        recordSyncState(provider, ProviderDataTypeEnum.ASIAN_ODDS, state, age, properties.asianOddsMaxSyncAge());
        alert(provider, "sync_stale", active && stale(age, properties.asianOddsMaxSyncAge()));
        boolean successfulOddsSeen = "SUCCESS".equals(state.latestStatus()) && !stale(age, properties.asianOddsMaxSyncAge());
        alert(provider, "coverage_low", active && successfulOddsSeen && poolCount > 0
                && coverage < properties.coverageMinimumRate().doubleValue());
        alert(provider, "sync_failure_streak", active && state.failedStreak() >= properties.failedRunStreakThreshold());
        alert(provider, "mapping_backlog", active && pendingMappings >= properties.pendingMappingThreshold());
        recordQuota(provider, ProviderDataTypeEnum.ASIAN_ODDS, businessDate, asianOddsProperties.quotaWarningThreshold(), active);
    }

    private void recordSyncState(
            String provider,
            ProviderDataTypeEnum dataType,
            SyncState state,
            double ageSeconds,
            Duration maxAge
    ) {
        String dataTypeCode = dataType.getCode();
        gauge("jingcai.datasource.sync.last_success_age", ageSeconds,
                "provider", provider, "data_type", dataTypeCode);
        gauge("jingcai.datasource.sync.max_age_threshold", maxAge.toSeconds(),
                "provider", provider, "data_type", dataTypeCode);
        gauge("jingcai.datasource.sync.failed_streak", state.failedStreak(),
                "provider", provider, "data_type", dataTypeCode);
    }

    private void recordQuota(
            String provider,
            ProviderDataTypeEnum dataType,
            LocalDate businessDate,
            int warningThreshold,
            boolean active
    ) {
        Instant from = businessDate.atStartOfDay(SHANGHAI).toInstant();
        Instant to = businessDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        double used = queryNumber("""
                SELECT COALESCE(SUM(quota_cost), 0)
                FROM data_sync_runs
                WHERE provider_code = ?
                  AND data_type = ?
                  AND started_at >= ?
                  AND started_at < ?
                """, provider, dataType.getCode(), Timestamp.from(from), Timestamp.from(to));
        gauge("jingcai.datasource.quota.used", used, "provider", provider, "data_type", dataType.getCode());
        if (warningThreshold > 0) {
            gauge("jingcai.datasource.quota.warning_threshold", warningThreshold,
                    "provider", provider, "data_type", dataType.getCode());
            alert(provider, "quota_threshold_reached", active && used >= warningThreshold);
        } else {
            alert(provider, "quota_threshold_reached", false);
        }
    }

    private SyncState readSyncState(String provider, ProviderDataTypeEnum dataType) {
        String latestStatus = jdbcTemplate.query("""
                SELECT sync_status
                FROM data_sync_runs
                WHERE provider_code = ?
                  AND data_type = ?
                  AND finished_at IS NOT NULL
                ORDER BY finished_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), provider, dataType.getCode())
                .stream()
                .findFirst()
                .orElse(null);
        Instant lastSuccess = jdbcTemplate.query("""
                SELECT finished_at
                FROM data_sync_runs
                WHERE provider_code = ?
                  AND data_type = ?
                  AND sync_status = 'SUCCESS'
                  AND finished_at IS NOT NULL
                ORDER BY finished_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getTimestamp(1).toInstant(), provider, dataType.getCode())
                .stream()
                .findFirst()
                .orElse(null);
        List<String> recentStatuses = jdbcTemplate.query("""
                SELECT sync_status
                FROM data_sync_runs
                WHERE provider_code = ?
                  AND data_type = ?
                  AND finished_at IS NOT NULL
                ORDER BY finished_at DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> rs.getString(1),
                provider, dataType.getCode(), properties.failedRunStreakThreshold());
        int failedStreak = 0;
        for (String status : recentStatuses) {
            if ("SUCCESS".equals(status)) {
                break;
            }
            if ("FAILED".equals(status) || "PARTIAL".equals(status)) {
                failedStreak++;
            }
        }
        return new SyncState(latestStatus, lastSuccess, failedStreak);
    }

    private boolean isSportteryActive() {
        return taskProperties.enabled()
                && (taskProperties.dataPipeline().enabled() || taskProperties.sportteryPool().enabled());
    }

    private boolean isAsianOddsActive() {
        return taskProperties.enabled()
                && (taskProperties.dataPipeline().enabled() || taskProperties.asianOdds().enabled());
    }

    private double ageSeconds(Instant lastSuccess) {
        Instant reference = lastSuccess == null ? startedAt : lastSuccess;
        return Math.max(Duration.between(reference, Instant.now()).toSeconds(), 0);
    }

    private boolean stale(double ageSeconds, Duration maximumAge) {
        return ageSeconds >= maximumAge.toSeconds();
    }

    private int queryInt(String sql, Object... args) {
        Number value = jdbcTemplate.queryForObject(sql, Number.class, args);
        return value == null ? 0 : value.intValue();
    }

    private double queryNumber(String sql, Object... args) {
        Number value = jdbcTemplate.queryForObject(sql, Number.class, args);
        return value == null ? 0.0 : value.doubleValue();
    }

    private void alert(String provider, String alert, boolean active) {
        gauge("jingcai.datasource.alert.active", active ? 1.0 : 0.0, "provider", provider, "alert", alert);
    }

    private void gauge(String name, double value, String... tags) {
        String key = name + '|' + String.join("|", tags);
        AtomicReference<Double> reference = gauges.computeIfAbsent(key, ignored -> {
            AtomicReference<Double> created = new AtomicReference<>(0.0);
            Gauge.builder(name, created, item -> item.get())
                    .tags(tags)
                    .register(meterRegistry);
            return created;
        });
        reference.set(value);
    }

    private record SyncState(String latestStatus, Instant lastSuccessAt, int failedStreak) {
    }
}
