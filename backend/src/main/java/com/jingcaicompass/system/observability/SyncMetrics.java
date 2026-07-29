package com.jingcaicompass.system.observability;

import com.jingcaicompass.data.entity.DataSyncRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 记录持久化 Provider 同步运行、记录数量、额度和耗时。 */
@Component
public class SyncMetrics {

    private final MeterRegistry meterRegistry;

    public SyncMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private SyncMetrics() {
        this.meterRegistry = null;
    }

    public static SyncMetrics noop() {
        return new SyncMetrics();
    }

    public void record(DataSyncRun run) {
        if (meterRegistry == null || run == null || run.getSyncStatus() == null) {
            return;
        }
        String provider = run.getProviderCode();
        String dataType = run.getDataType() == null ? "UNKNOWN" : run.getDataType().getCode();
        String status = run.getSyncStatus().getCode();
        Counter.builder("jingcai.sync.run")
                .tags("provider", provider, "data_type", dataType, "status", status)
                .register(meterRegistry)
                .increment();
        recordCount(provider, dataType, "fetched", run.getFetchedCount());
        recordCount(provider, dataType, "success", run.getSuccessCount());
        recordCount(provider, dataType, "failure", run.getFailureCount());
        recordCount(provider, dataType, "quota", run.getQuotaCost());
        if (run.getStartedAt() != null && run.getFinishedAt() != null) {
            Duration duration = Duration.between(run.getStartedAt(), run.getFinishedAt());
            Timer.builder("jingcai.sync.duration")
                    .tags("provider", provider, "data_type", dataType, "status", status)
                    .register(meterRegistry)
                    .record(duration.isNegative() ? Duration.ZERO : duration);
        }
    }

    private void recordCount(String provider, String dataType, String kind, Integer value) {
        if (value == null || value <= 0) {
            return;
        }
        Counter.builder("jingcai.sync.record")
                .tags("provider", provider, "data_type", dataType, "kind", kind)
                .register(meterRegistry)
                .increment(value);
    }
}
