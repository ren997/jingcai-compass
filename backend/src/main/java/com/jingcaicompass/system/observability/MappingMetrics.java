package com.jingcaicompass.system.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 记录比赛映射决策；不将外部或内部比赛 ID 用作标签。 */
@Component
public class MappingMetrics {

    private final MeterRegistry meterRegistry;

    public MappingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private MappingMetrics() {
        this.meterRegistry = null;
    }

    public static MappingMetrics noop() {
        return new MappingMetrics();
    }

    public void recordDecision(String providerCode, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("jingcai.mapping.decision")
                .tags("provider", providerCode, "outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
