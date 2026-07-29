package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.system.infrastructure.TraceIdContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class JobMetricsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void createsTraceAndJobContextThenRestoresMdcAfterFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            JobMetrics metrics = new JobMetrics(registry);
            JobMetrics.JobExecution execution = metrics.start("asian_odds_sync");

            assertThat(MDC.get(TraceIdContext.MDC_KEY)).hasSize(32);
            assertThat(MDC.get("jobName")).isEqualTo("asian_odds_sync");
            execution.recordOutcome("FAILED");
            assertThat(MDC.get("status")).isEqualTo("FAILED");
            assertThat(MDC.get("durationMs")).matches("\\d+");
            metrics.record(execution, "FAILED");

            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            assertThat(registry.get("jingcai.job.execution")
                    .tags("job", "asian_odds_sync", "status", "FAILED")
                    .counter().count()).isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }
}
