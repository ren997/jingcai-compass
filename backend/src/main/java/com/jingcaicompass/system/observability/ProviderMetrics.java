package com.jingcaicompass.system.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 记录 Provider HTTP 请求与重试；标签仅使用稳定 Provider 与结果分类。 */
@Component
public class ProviderMetrics {

    private final MeterRegistry meterRegistry;

    public ProviderMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private ProviderMetrics() {
        this.meterRegistry = null;
    }

    public static ProviderMetrics noop() {
        return new ProviderMetrics();
    }

    public void recordRequest(String providerCode, String result) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("jingcai.provider.request")
                .tags("provider", providerCode, "result", result)
                .register(meterRegistry)
                .increment();
    }

    public void recordRetry(String providerCode) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("jingcai.provider.retry")
                .tag("provider", providerCode)
                .register(meterRegistry)
                .increment();
    }
}
