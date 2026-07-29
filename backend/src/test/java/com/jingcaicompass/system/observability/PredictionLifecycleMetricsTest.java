package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PredictionLifecycleMetricsTest {

    @Test
    void exposesLifecycleMetersWithOnlyFixedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            PredictionLifecycleMetrics metrics = new PredictionLifecycleMetrics(registry);

            metrics.recordOverdueLocks(2);
            metrics.recordSettlementBacklog(3);
            metrics.recordSettlementItem("settle", "failed");
            metrics.recordSettlementItem("recalculate", "recalculated");
            metrics.recordSnapshotPublish("failed");
            metrics.recordSnapshotHashMismatch();
            metrics.recordAlert("prediction_lock", "overdue", true);
            metrics.recordAlert("settlement", "backlog_overdue", false);

            assertThat(registry.get("jingcai.prediction.lock.overdue").gauge().value()).isEqualTo(2.0);
            assertThat(registry.get("jingcai.settlement.backlog.predictions").gauge().value()).isEqualTo(3.0);
            assertThat(registry.get("jingcai.settlement.item")
                    .tags("operation", "settle", "result", "failed").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("jingcai.settlement.item")
                    .tags("operation", "recalculate", "result", "recalculated").counter().count())
                    .isEqualTo(1.0);
            assertThat(registry.get("jingcai.snapshot.publish")
                    .tag("result", "failed").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("jingcai.snapshot.integrity.failure")
                    .tag("reason", "HASH_MISMATCH").counter().count()).isEqualTo(1.0);
            assertThat(alert(registry, "snapshot", "publish_failed")).isEqualTo(1.0);
            assertThat(alert(registry, "snapshot", "hash_mismatch")).isEqualTo(1.0);
            assertThat(registry.get("jingcai.lifecycle.alert.active")
                    .tags("component", "prediction_lock", "alert", "overdue").gauge().value()).isEqualTo(1.0);
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                    .noneMatch(tag -> tag.getKey().equals("predictionId")
                            || tag.getKey().equals("matchId")
                            || tag.getKey().equals("snapshotId")
                            || tag.getKey().equals("traceId")));
        } finally {
            registry.close();
        }
    }

    @Test
    void successfulSnapshotClearsStateAlertsWithoutErasingFailureCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            PredictionLifecycleMetrics metrics = new PredictionLifecycleMetrics(registry);
            metrics.recordSnapshotHashMismatch();
            metrics.recordSnapshotPublish("failed");
            metrics.recordSnapshotPublish("published");

            assertThat(alert(registry, "snapshot", "publish_failed")).isZero();
            assertThat(alert(registry, "snapshot", "hash_mismatch")).isZero();
            assertThat(registry.get("jingcai.snapshot.publish")
                    .tag("result", "failed").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("jingcai.snapshot.integrity.failure")
                    .tag("reason", "HASH_MISMATCH").counter().count()).isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }

    private static double alert(SimpleMeterRegistry registry, String component, String alert) {
        return registry.get("jingcai.lifecycle.alert.active")
                .tags("component", component, "alert", alert)
                .gauge()
                .value();
    }
}
