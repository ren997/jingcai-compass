package com.jingcaicompass.settlement.service;

import com.jingcaicompass.settlement.dto.SettlementRecalculationBatchResultDto;
import com.jingcaicompass.settlement.exception.SettlementManualReviewException;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 批量重算编排：逐条隔离赛果修正造成的结算版本替代。 */
@Service
@ConditionalOnBean(DataSource.class)
public class SettlementRecalculationServiceImpl implements SettlementRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementRecalculationServiceImpl.class);

    private final SettlementMapper settlementMapper;
    private final SettlementRecalculationWriter recalculationWriter;

    public SettlementRecalculationServiceImpl(
            SettlementMapper settlementMapper,
            SettlementRecalculationWriter recalculationWriter
    ) {
        this.settlementMapper = settlementMapper;
        this.recalculationWriter = recalculationWriter;
    }

    @Override
    public SettlementRecalculationBatchResultDto recalculateOutdatedSettlements(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        // 1) 只扫描当前结算引用已被替代 FINAL/VOID 事实的锁定预测。
        List<Long> candidateIds = settlementMapper.selectOutdatedLockedPredictionIds(batchSize);
        BatchCounters counters = new BatchCounters(candidateIds.size());

        // 2) 每条预测使用独立事务，单条人工处理或回滚不阻塞本批其他预测。
        for (Long predictionId : candidateIds) {
            try {
                counters.record(recalculationWriter.recalculatePrediction(predictionId));
            } catch (SettlementManualReviewException exception) {
                counters.manualReviewCount++;
                log.warn("settlement recalculation requires manual review predictionId={} reason={}",
                        predictionId, exception.getMessage());
            } catch (RuntimeException exception) {
                counters.failureCount++;
                log.error("settlement recalculation failed predictionId={}", predictionId, exception);
            }
        }

        // 3) 返回独立于普通待结算扫描的真实重算摘要。
        return counters.toResult();
    }

    private static final class BatchCounters {

        private final int candidateCount;
        private int recalculatedPredictionCount;
        private int recalculatedMarketCount;
        private int skippedCount;
        private int failureCount;
        private int manualReviewCount;

        private BatchCounters(int candidateCount) {
            this.candidateCount = candidateCount;
        }

        private void record(SettlementRecalculationWriteResult result) {
            if (result.outcome() == SettlementRecalculationWriteResult.Outcome.RECALCULATED) {
                recalculatedPredictionCount++;
                recalculatedMarketCount += result.recalculatedMarketCount();
                return;
            }
            skippedCount++;
        }

        private SettlementRecalculationBatchResultDto toResult() {
            return new SettlementRecalculationBatchResultDto(
                    candidateCount,
                    recalculatedPredictionCount,
                    recalculatedMarketCount,
                    skippedCount,
                    failureCount,
                    manualReviewCount
            );
        }
    }
}
