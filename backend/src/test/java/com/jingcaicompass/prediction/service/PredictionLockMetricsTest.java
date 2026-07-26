package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PredictionLockMetricsTest {

    @Test
    void exposesOnlyLowCardinalityLockMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PredictionLockMetrics metrics = new PredictionLockMetrics(registry);

        metrics.recordLocked(Duration.ofSeconds(2));
        metrics.recordItemFailure();
        metrics.recordBatchFailure();
        metrics.recordBatch(Duration.ofMillis(30), "partial");

        assertThat(registry.get("jingcai.prediction.lock.records")
                .tag("result", "locked")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("jingcai.prediction.lock.records")
                .tag("result", "failed")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("jingcai.prediction.lock.delay")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(registry.get("jingcai.prediction.lock.batch.duration")
                .tag("result", "partial")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get("jingcai.prediction.lock.exceptions")
                .tag("stage", "item")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("jingcai.prediction.lock.exceptions")
                .tag("stage", "batch")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().equals("predictionId")));
    }
}
