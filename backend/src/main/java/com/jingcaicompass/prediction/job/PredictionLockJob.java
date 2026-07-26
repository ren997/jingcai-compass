package com.jingcaicompass.prediction.job;

import com.jingcaicompass.prediction.dto.PredictionLockResultDto;
import com.jingcaicompass.prediction.service.PredictionLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时锁定已到 PostgreSQL 截止时间的公开预测。 */
@Component
@ConditionalOnBean(PredictionLockService.class)
@ConditionalOnExpression("${app.tasks.enabled:false} && ${app.tasks.prediction-lock.enabled:false}")
public class PredictionLockJob {

    private static final Logger log = LoggerFactory.getLogger(PredictionLockJob.class);

    private final PredictionLockService predictionLockService;

    @Value("${app.tasks.prediction-lock.batch-size:100}")
    private int batchSize;

    public PredictionLockJob(PredictionLockService predictionLockService) {
        this.predictionLockService = predictionLockService;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.prediction-lock.fixed-delay}",
            initialDelayString = "${app.tasks.prediction-lock.initial-delay}"
    )
    public void lockDuePredictions() {
        // 1) 调用唯一批量锁定入口，时间边界由 PostgreSQL 判断
        log.info("prediction lock job started jobName=PredictionLockJob batchSize={}", batchSize);
        try {
            PredictionLockResultDto result =
                    predictionLockService.lockDuePredictions(batchSize);

            // 2) 输出锁定数、失败数和耗时，明细通过审计与异常日志追踪
            log.info(
                    "prediction lock job finished jobName=PredictionLockJob "
                            + "locked={} failed={} durationMs={}",
                    result.lockedCount(),
                    result.failedCount(),
                    result.durationMs()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "prediction lock job failed jobName=PredictionLockJob batchSize={}",
                    batchSize,
                    exception
            );
            throw exception;
        }
    }
}
