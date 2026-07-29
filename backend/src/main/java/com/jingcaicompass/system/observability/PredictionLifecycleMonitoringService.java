package com.jingcaicompass.system.observability;

import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 从持久化预测、赛果与结算事实刷新生命周期积压指标和告警状态。 */
@Component
@ConditionalOnBean(DataSource.class)
public class PredictionLifecycleMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(PredictionLifecycleMonitoringService.class);

    private final PredictionMapper predictionMapper;
    private final SettlementMapper settlementMapper;
    private final PredictionLifecycleMetrics metrics;
    private final ObservabilityProperties properties;
    private final SyncTaskProperties taskProperties;

    public PredictionLifecycleMonitoringService(
            PredictionMapper predictionMapper,
            SettlementMapper settlementMapper,
            PredictionLifecycleMetrics metrics,
            ObservabilityProperties properties,
            SyncTaskProperties taskProperties
    ) {
        this.predictionMapper = predictionMapper;
        this.settlementMapper = settlementMapper;
        this.metrics = metrics;
        this.properties = properties;
        this.taskProperties = taskProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${app.observability.refresh-delay:1m}")
    public void refreshScheduled() {
        refresh();
    }

    /** 使用同一次数据库时间快照刷新数量；查询故障不输出业务内容或异常正文。 */
    public void refresh() {
        if (!properties.enabled()) {
            return;
        }
        try {
            // 1) 统一使用 PostgreSQL 当前时间，避免应用节点时钟漂移影响积压判定。
            Instant databaseTime = predictionMapper.selectDatabaseTime();
            long overdueLocks = predictionMapper.countOverduePublishedPredictions(
                    databaseTime.minus(properties.predictionLockOverdueGrace())
            );
            long settlementBacklog = settlementMapper.countOverdueSettlementBacklog(
                    databaseTime.minus(properties.settlementBacklogGrace())
            );

            // 2) 数量始终可观察；关闭任务时仅抑制告警，不掩盖持久化事实。
            metrics.recordOverdueLocks(overdueLocks);
            metrics.recordSettlementBacklog(settlementBacklog);
            metrics.recordAlert(
                    "prediction_lock",
                    "overdue",
                    isPredictionLockActive() && overdueLocks > 0
            );
            metrics.recordAlert(
                    "settlement",
                    "backlog_overdue",
                    isSettlementActive() && settlementBacklog > 0
            );
        } catch (RuntimeException exception) {
            log.error("event=prediction_lifecycle_monitor_failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private boolean isPredictionLockActive() {
        return taskProperties.enabled() && taskProperties.predictionLock().enabled();
    }

    private boolean isSettlementActive() {
        return taskProperties.enabled() && taskProperties.settlement().enabled();
    }
}
