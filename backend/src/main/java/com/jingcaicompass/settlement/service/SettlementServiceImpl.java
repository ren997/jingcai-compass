package com.jingcaicompass.settlement.service;

import com.jingcaicompass.settlement.dto.SettlementBatchResultDto;
import com.jingcaicompass.settlement.exception.SettlementManualReviewException;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import com.jingcaicompass.system.observability.MdcScope;
import com.jingcaicompass.system.observability.PredictionLifecycleMetrics;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 批量结算编排：隔离单条预测失败，不持有跨预测事务。 */
@Service
@ConditionalOnBean(DataSource.class)
public class SettlementServiceImpl implements SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementServiceImpl.class);

    private final SettlementMapper settlementMapper;
    private final SettlementWriter settlementWriter;
    private final PredictionLifecycleMetrics lifecycleMetrics;

    public SettlementServiceImpl(
            SettlementMapper settlementMapper,
            SettlementWriter settlementWriter,
            PredictionLifecycleMetrics lifecycleMetrics
    ) {
        this.settlementMapper = settlementMapper;
        this.settlementWriter = settlementWriter;
        this.lifecycleMetrics = lifecycleMetrics;
    }

    @Override
    public SettlementBatchResultDto settlePendingPredictions(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        // 1) 只取已锁定且拥有当前 FINAL/VOID 事实的待结算预测。
        List<Long> candidateIds = settlementMapper.selectPendingLockedPredictionIds(batchSize);
        BatchCounters counters = new BatchCounters(candidateIds.size());

        // 2) 每条预测委派到独立事务，单条回滚不会阻塞本批其他比赛。
        for (Long predictionId : candidateIds) {
            try (MdcScope ignored = MdcScope.prediction(predictionId)) {
                try {
                    SettlementWriteResult result = settlementWriter.settlePrediction(predictionId);
                    counters.record(result);
                    lifecycleMetrics.recordSettlementItem(
                            "settle",
                            result.outcome() == SettlementWriteResult.Outcome.SETTLED ? "settled" : "skipped"
                    );
                } catch (SettlementManualReviewException exception) {
                    counters.manualReviewCount++;
                    lifecycleMetrics.recordSettlementItem("settle", "manual_review");
                    log.warn("event=settlement_item_manual_review exceptionType={}",
                            exception.getClass().getSimpleName());
                } catch (RuntimeException exception) {
                    counters.failureCount++;
                    lifecycleMetrics.recordSettlementItem("settle", "failed");
                    log.error("event=settlement_item_failed exceptionType={}",
                            exception.getClass().getSimpleName());
                }
            }
        }

        // 3) 返回可供 Job 日志与后续监控使用的真实批次摘要。
        return counters.toResult();
    }

    private static final class BatchCounters {

        private final int candidateCount;
        private int settledPredictionCount;
        private int settledMarketCount;
        private int skippedCount;
        private int failureCount;
        private int manualReviewCount;

        private BatchCounters(int candidateCount) {
            this.candidateCount = candidateCount;
        }

        private void record(SettlementWriteResult result) {
            if (result.outcome() == SettlementWriteResult.Outcome.SETTLED) {
                settledPredictionCount++;
                settledMarketCount += result.settledMarketCount();
                return;
            }
            skippedCount++;
        }

        private SettlementBatchResultDto toResult() {
            return new SettlementBatchResultDto(
                    candidateCount,
                    settledPredictionCount,
                    settledMarketCount,
                    skippedCount,
                    failureCount,
                    manualReviewCount
            );
        }
    }
}
