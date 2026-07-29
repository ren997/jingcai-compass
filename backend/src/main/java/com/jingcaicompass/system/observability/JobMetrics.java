package com.jingcaicompass.system.observability;

import com.jingcaicompass.system.infrastructure.TraceIdContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 记录定时任务结果，并在任务线程建立可关联的 MDC 上下文。 */
@Component
public class JobMetrics {

    private final MeterRegistry meterRegistry;

    public JobMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private JobMetrics() {
        this.meterRegistry = null;
    }

    public static JobMetrics noop() {
        return new JobMetrics();
    }

    public JobExecution start(String jobName) {
        return new JobExecution(jobName, System.nanoTime(), MDC.get(TraceIdContext.MDC_KEY), MDC.get("jobName"));
    }

    public void record(JobExecution execution, String status) {
        try {
            execution.recordOutcome(status);
            if (meterRegistry == null) {
                return;
            }
            Counter.builder("jingcai.job.execution")
                    .tags("job", execution.jobName(), "status", status)
                    .register(meterRegistry)
                    .increment();
            Timer.builder("jingcai.job.duration")
                    .tags("job", execution.jobName(), "status", status)
                    .register(meterRegistry)
                    .record(Duration.ofNanos(Math.max(System.nanoTime() - execution.startedNanos(), 0)));
        } finally {
            execution.close();
        }
    }

    /** 一次定时任务执行期间的 MDC 范围。 */
    public static final class JobExecution implements AutoCloseable {
        private final String jobName;
        private final long startedNanos;
        private final String priorTraceId;
        private final String priorJobName;
        private final String priorStatus;
        private final String priorDurationMs;

        private JobExecution(String jobName, long startedNanos, String priorTraceId, String priorJobName) {
            this.jobName = jobName;
            this.startedNanos = startedNanos;
            this.priorTraceId = priorTraceId;
            this.priorJobName = priorJobName;
            this.priorStatus = MDC.get("status");
            this.priorDurationMs = MDC.get("durationMs");
            if (priorTraceId == null || priorTraceId.isBlank()) {
                MDC.put(TraceIdContext.MDC_KEY, UUID.randomUUID().toString().replace("-", ""));
            }
            MDC.put("jobName", jobName);
        }

        public String jobName() {
            return jobName;
        }

        long startedNanos() {
            return startedNanos;
        }

        public long durationMs() {
            return Math.max((System.nanoTime() - startedNanos) / 1_000_000, 0);
        }

        /** 将最终状态和耗时写入当前任务日志上下文。 */
        public void recordOutcome(String status) {
            MDC.put("status", status == null || status.isBlank() ? "UNKNOWN" : status);
            MDC.put("durationMs", String.valueOf(durationMs()));
        }

        @Override
        public void close() {
            restore(TraceIdContext.MDC_KEY, priorTraceId);
            restore("jobName", priorJobName);
            restore("status", priorStatus);
            restore("durationMs", priorDurationMs);
        }

        private void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
