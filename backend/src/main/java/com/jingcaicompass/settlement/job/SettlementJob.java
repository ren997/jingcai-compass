package com.jingcaicompass.settlement.job;

import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
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

    public SettlementJob(
            SettlementRecalculationService recalculationService,
            SettlementService settlementService,
            SyncTaskProperties taskProperties
    ) {
        this.recalculationService = recalculationService;
        this.settlementService = settlementService;
        this.taskProperties = taskProperties;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.settlement.fixed-delay}",
            initialDelayString = "${app.tasks.settlement.initial-delay}"
    )
    public void settlePendingPredictions() {
        int batchSize = taskProperties.settlement().batchSize();

        // 1) 先替代被官方修正事实淘汰的结算版本，保留旧历史供追溯。
        log.info("settlement job started jobName=SettlementJob batchSize={}", batchSize);
        var recalculationResult = recalculationService.recalculateOutdatedSettlements(batchSize);
        log.info(
                "settlement recalculation finished jobName=SettlementJob candidates={} recalculatedPredictions={} "
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

        // 3) 输出普通待结算扫描的候选、成功、失败和需人工补齐输入数量。
        log.info(
                "settlement job finished jobName=SettlementJob candidates={} settledPredictions={} "
                        + "settledMarkets={} skipped={} failed={} manualReview={}",
                result.candidatePredictionCount(),
                result.settledPredictionCount(),
                result.settledMarketCount(),
                result.skippedPredictionCount(),
                result.failedPredictionCount(),
                result.manualReviewPredictionCount()
        );
    }
}
