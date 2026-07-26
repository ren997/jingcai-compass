package com.jingcaicompass.prediction.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 记录预测锁定数量、延迟、批次耗时和低基数异常指标。 */
@Component
public class PredictionLockMetrics {

    private final Counter lockedRecords;
    private final Counter failedRecords;
    private final Counter itemExceptions;
    private final Counter batchExceptions;
    private final Timer lockDelay;
    private final Timer successBatchDuration;
    private final Timer partialBatchDuration;
    private final Timer failedBatchDuration;

    public PredictionLockMetrics(MeterRegistry meterRegistry) {
        this.lockedRecords = Counter.builder("jingcai.prediction.lock.records")
                .tag("result", "locked")
                .register(meterRegistry);
        this.failedRecords = Counter.builder("jingcai.prediction.lock.records")
                .tag("result", "failed")
                .register(meterRegistry);
        this.itemExceptions = Counter.builder("jingcai.prediction.lock.exceptions")
                .tag("stage", "item")
                .register(meterRegistry);
        this.batchExceptions = Counter.builder("jingcai.prediction.lock.exceptions")
                .tag("stage", "batch")
                .register(meterRegistry);
        this.lockDelay = Timer.builder("jingcai.prediction.lock.delay")
                .register(meterRegistry);
        this.successBatchDuration = batchTimer(meterRegistry, "success");
        this.partialBatchDuration = batchTimer(meterRegistry, "partial");
        this.failedBatchDuration = batchTimer(meterRegistry, "failed");
    }

    public void recordLocked(Duration delay) {
        lockedRecords.increment();
        lockDelay.record(delay.isNegative() ? Duration.ZERO : delay);
    }

    public void recordItemFailure() {
        failedRecords.increment();
        itemExceptions.increment();
    }

    public void recordBatchFailure() {
        batchExceptions.increment();
    }

    public void recordBatch(Duration duration, String result) {
        switch (result) {
            case "success" -> successBatchDuration.record(duration);
            case "partial" -> partialBatchDuration.record(duration);
            case "failed" -> failedBatchDuration.record(duration);
            default -> throw new IllegalArgumentException("unsupported lock batch result: " + result);
        }
    }

    private Timer batchTimer(MeterRegistry meterRegistry, String result) {
        return Timer.builder("jingcai.prediction.lock.batch.duration")
                .tag("result", result)
                .register(meterRegistry);
    }
}
