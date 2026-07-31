package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionLockResultDto;
import com.jingcaicompass.system.observability.MdcScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 编排独立事务 Worker，批量锁定到期预测并汇总可观测结果。 */
@Service
@ConditionalOnBean(DataSource.class)
public class PredictionLockServiceImpl implements PredictionLockService {

    private static final Logger log = LoggerFactory.getLogger(PredictionLockServiceImpl.class);
    private static final int MAX_BATCH_SIZE = 1000;
    private static final int MAX_EMPTY_RETRIES = 5;
    private static final long EMPTY_RETRY_BACKOFF_NANOS = 2_000_000L;

    private final PredictionLockWorker predictionLockWorker;
    private final PredictionLockMetrics predictionLockMetrics;

    public PredictionLockServiceImpl(
            PredictionLockWorker predictionLockWorker,
            PredictionLockMetrics predictionLockMetrics
    ) {
        this.predictionLockWorker = predictionLockWorker;
        this.predictionLockMetrics = predictionLockMetrics;
    }

    @Override
    public PredictionLockResultDto lockDuePredictions(int batchSize) {
        requireBatchSize(batchSize);
        long startedAt = System.nanoTime();
        List<Long> lockedIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();
        Set<Long> excludedIds = new LinkedHashSet<>();
        int emptyRetries = 0;

        try {
            // 1) 逐条抢占到期记录，最多处理配置的批大小
            while (lockedIds.size() + failedIds.size() < batchSize) {
                PredictionLockWorker.LockResult item;
                try {
                    item = predictionLockWorker.lockNext(excludedIds);
                } catch (PredictionLockItemException exception) {
                    // 2) 单条失败加入本批排除集，保留其他预测继续处理
                    failedIds.add(exception.predictionId());
                    excludedIds.add(exception.predictionId());
                    predictionLockMetrics.recordItemFailure();
                    try (MdcScope ignored = MdcScope.prediction(exception.predictionId())) {
                        log.warn("event=prediction_lock_item_failed stage={} exceptionType={}",
                                exception.stage(), exception.getClass().getSimpleName());
                    }
                    continue;
                }
                if (item == null) {
                    // SKIP LOCKED 可能只是在本轮遇到短暂竞争；有界重试后再结束本批。
                    if (emptyRetries++ >= MAX_EMPTY_RETRIES) {
                        break;
                    }
                    LockSupport.parkNanos(EMPTY_RETRY_BACKOFF_NANOS);
                    continue;
                }

                // 3) 记录成功 ID 和基于数据库时间计算的锁定延迟
                emptyRetries = 0;
                lockedIds.add(item.predictionId());
                predictionLockMetrics.recordLocked(
                        Duration.between(item.lockTime(), item.lockedAt())
                );
            }

            // 4) 汇总批次结果和低基数耗时指标
            Duration duration = elapsed(startedAt);
            String outcome = batchOutcome(lockedIds, failedIds);
            predictionLockMetrics.recordBatch(duration, outcome);
            return new PredictionLockResultDto(
                    lockedIds.size(),
                    failedIds.size(),
                    lockedIds,
                    failedIds,
                    duration.toMillis()
            );
        } catch (RuntimeException exception) {
            Duration duration = elapsed(startedAt);
            predictionLockMetrics.recordBatchFailure();
            predictionLockMetrics.recordBatch(duration, "failed");
            throw exception;
        }
    }

    private void requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "prediction lock batchSize must be between 1 and " + MAX_BATCH_SIZE
            );
        }
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private String batchOutcome(List<Long> lockedIds, List<Long> failedIds) {
        if (failedIds.isEmpty()) {
            return "success";
        }
        return lockedIds.isEmpty() ? "failed" : "partial";
    }
}
