package com.jingcaicompass.system.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 记录预测锁定、结算积压和快照发布的固定维度业务指标。 */
@Component
public class PredictionLifecycleMetrics {

    private final AtomicReference<Double> overdueLocks = new AtomicReference<>(0.0);
    private final AtomicReference<Double> settlementBacklog = new AtomicReference<>(0.0);
    private final Map<String, AtomicReference<Double>> alerts = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public PredictionLifecycleMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("jingcai.prediction.lock.overdue", overdueLocks, value -> value.get())
                .register(meterRegistry);
        Gauge.builder("jingcai.settlement.backlog.predictions", settlementBacklog, value -> value.get())
                .register(meterRegistry);
    }

    public void recordOverdueLocks(long count) {
        overdueLocks.set((double) Math.max(count, 0));
    }

    public void recordSettlementBacklog(long count) {
        settlementBacklog.set((double) Math.max(count, 0));
    }

    public void recordSettlementItem(String operation, String result) {
        Counter.builder("jingcai.settlement.item")
                .tags("operation", requireOperation(operation), "result", requireSettlementResult(result))
                .register(meterRegistry)
                .increment();
    }

    public void recordSnapshotPublish(String result) {
        String normalizedResult = requireSnapshotResult(result);
        Counter.builder("jingcai.snapshot.publish")
                .tag("result", normalizedResult)
                .register(meterRegistry)
                .increment();
        recordAlert("snapshot", "publish_failed", "failed".equals(normalizedResult));
        if (!"failed".equals(normalizedResult)) {
            recordAlert("snapshot", "hash_mismatch", false);
        }
    }

    public void recordSnapshotHashMismatch() {
        Counter.builder("jingcai.snapshot.integrity.failure")
                .tag("reason", "HASH_MISMATCH")
                .register(meterRegistry)
                .increment();
        recordAlert("snapshot", "hash_mismatch", true);
    }

    public void recordAlert(String component, String alert, boolean active) {
        String normalizedComponent = requireComponent(component);
        String normalizedAlert = requireAlert(alert);
        String key = normalizedComponent + '|' + normalizedAlert;
        AtomicReference<Double> value = alerts.computeIfAbsent(key, ignored -> {
            AtomicReference<Double> reference = new AtomicReference<>(0.0);
            Gauge.builder("jingcai.lifecycle.alert.active", reference, item -> item.get())
                    .tags("component", normalizedComponent, "alert", normalizedAlert)
                    .register(meterRegistry);
            return reference;
        });
        value.set(active ? 1.0 : 0.0);
    }

    private String requireOperation(String value) {
        if ("settle".equals(value) || "recalculate".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("unsupported settlement operation");
    }

    private String requireSettlementResult(String value) {
        if ("settled".equals(value) || "recalculated".equals(value)
                || "skipped".equals(value) || "manual_review".equals(value)
                || "failed".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("unsupported settlement result");
    }

    private String requireSnapshotResult(String value) {
        if ("published".equals(value) || "reused".equals(value) || "failed".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("unsupported snapshot result");
    }

    private String requireComponent(String value) {
        if ("prediction_lock".equals(value) || "settlement".equals(value)
                || "snapshot".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("unsupported lifecycle component");
    }

    private String requireAlert(String value) {
        if ("overdue".equals(value) || "backlog_overdue".equals(value)
                || "publish_failed".equals(value) || "hash_mismatch".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("unsupported lifecycle alert");
    }
}
