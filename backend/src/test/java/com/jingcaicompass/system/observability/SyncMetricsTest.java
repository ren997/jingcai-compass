package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SyncMetricsTest {

    @Test
    void recordsRunCountsQuotaAndDurationWithoutBusinessIdTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            SyncMetrics metrics = new SyncMetrics(registry);
            DataSyncRun run = new DataSyncRun();
            run.setId(99L);
            run.setProviderCode("THE_ODDS_API");
            run.setDataType(ProviderDataTypeEnum.ASIAN_ODDS);
            run.setSyncStatus(SyncStatusEnum.PARTIAL);
            run.setStartedAt(Instant.parse("2026-07-29T00:00:00Z"));
            run.setFinishedAt(Instant.parse("2026-07-29T00:00:02Z"));
            run.setFetchedCount(4);
            run.setSuccessCount(3);
            run.setFailureCount(1);
            run.setQuotaCost(2);

            metrics.record(run);

            assertThat(registry.get("jingcai.sync.run")
                    .tags("provider", "THE_ODDS_API", "data_type", "ASIAN_ODDS", "status", "PARTIAL")
                    .counter().count()).isEqualTo(1.0);
            assertThat(registry.get("jingcai.sync.record")
                    .tags("provider", "THE_ODDS_API", "data_type", "ASIAN_ODDS", "kind", "quota")
                    .counter().count()).isEqualTo(2.0);
            assertThat(registry.get("jingcai.sync.duration")
                    .tags("provider", "THE_ODDS_API", "data_type", "ASIAN_ODDS", "status", "PARTIAL")
                    .timer().count()).isEqualTo(1L);
            assertThat(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                    .map(tag -> tag.getKey()).toList())
                    .doesNotContain("traceId", "syncRunId", "matchId", "predictionId");
        } finally {
            registry.close();
        }
    }
}
