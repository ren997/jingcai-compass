package com.jingcaicompass.settlement.job;

import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import com.jingcaicompass.system.observability.JobMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 默认关闭的自动结算 Job，按批次持续补齐已确认赛果。 */
@Component
@ConditionalOnBean(SettlementService.class)
@ConditionalOnExpression("${app.tasks.enabled:false} && ${app.tasks.settlement.enabled:false}")
public class SettlementJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);

    private final SettlementRecalculationService recalculationService;
    private final SettlementService settlementService;
    private final SyncTaskProperties taskProperties;
    private final JobMetrics jobMetrics;

    public SettlementJob(
            SettlementRecalculationService recalculationService,
            SettlementService settlementService,
            SyncTaskProperties taskProperties,
            JobMetrics jobMetrics
    ) {
        this.recalculationService = recalculationService;
        this.settlementService = settlementService;
        this.taskProperties = taskProperties;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "#{@syncTaskSchedule.settlementFixedDelayMillis()}",
            initialDelayString = "#{@syncTaskSchedule.settlementInitialDelayMillis()}"
    )
    public void settlePendingPredictions() {
        JobMetrics.JobExecution execution = jobMetrics.start("settlement");
        int batchSize = taskProperties.settlement().batchSize();

        try {
            // 1) 先替代被官方修正事实淘汰的结算版本，保留旧历史供追溯。
            log.info("event=settlement_job_started batchSize={}", batchSize);
            var recalculationResult = recalculationService.recalculateOutdatedSettlements(batchSize);
            log.info(
                    "event=settlement_recalculation_finished candidates={} recalculatedPredictions={} "
                            + "recalculatedMarkets={} skipped={} failed={} manualReview={}",
                    recalculationResult.candidatePredictionCount(),
                    recalculationResult.recalculatedPredictionCount(),
                    recalculationResult.recalculatedMarketCount(),
                    recalculationResult.skippedPredictionCount(),
                    recalculationResult.failedPredictionCount(),
                    recalculationResult.manualReviewPredictionCount()
            );

            // 2) 再补齐尚未产生首版结算的预测，两个批次均受相同上限约束。
            var result = settlementService.settlePendingPredictions(batchSize);

            // 3) 输出普通待结算扫描摘要，失败与人工复核均标记为部分完成。
            log.info(
                    "event=settlement_job_finished candidates={} settledPredictions={} "
                            + "settledMarkets={} skipped={} failed={} manualReview={}",
                    result.candidatePredictionCount(),
                    result.settledPredictionCount(),
                    result.settledMarketCount(),
                    result.skippedPredictionCount(),
                    result.failedPredictionCount(),
                    result.manualReviewPredictionCount()
            );
            boolean partial = recalculationResult.failedPredictionCount() > 0
                    || recalculationResult.manualReviewPredictionCount() > 0
                    || result.failedPredictionCount() > 0
                    || result.manualReviewPredictionCount() > 0;
            jobMetrics.record(execution, partial ? "PARTIAL" : "SUCCESS");
        } catch (RuntimeException exception) {
            log.error("event=settlement_job_failed batchSize={} exceptionType={}",
                    batchSize, exception.getClass().getSimpleName());
            jobMetrics.record(execution, "FAILED");
            throw exception;
        }
    }
}
