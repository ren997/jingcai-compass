package com.jingcaicompass.settlement.job;

import com.jingcaicompass.settlement.service.SettlementService;
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

    private final SettlementService settlementService;
    private final SyncTaskProperties taskProperties;

    public SettlementJob(
            SettlementService settlementService,
            SyncTaskProperties taskProperties
    ) {
        this.settlementService = settlementService;
        this.taskProperties = taskProperties;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.settlement.fixed-delay}",
            initialDelayString = "${app.tasks.settlement.initial-delay}"
    )
    public void settlePendingPredictions() {
        int batchSize = taskProperties.settlement().batchSize();

        // 1) 使用配置的有界批量扫描，避免 Job 长事务占用赛果写入。
        log.info("settlement job started jobName=SettlementJob batchSize={}", batchSize);
        var result = settlementService.settlePendingPredictions(batchSize);

        // 2) 输出候选、成功、失败和需人工补齐输入的数量。
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
