package com.jingcaicompass.prediction.job;

import com.jingcaicompass.prediction.dto.PredictionLockResultDto;
import com.jingcaicompass.prediction.service.PredictionLockService;
import com.jingcaicompass.system.observability.JobMetrics;
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
    private final JobMetrics jobMetrics;

    @Value("${app.tasks.prediction-lock.batch-size:100}")
    private int batchSize;

    public PredictionLockJob(PredictionLockService predictionLockService, JobMetrics jobMetrics) {
        this.predictionLockService = predictionLockService;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.prediction-lock.fixed-delay}",
            initialDelayString = "${app.tasks.prediction-lock.initial-delay}"
    )
    public void lockDuePredictions() {
        JobMetrics.JobExecution execution = jobMetrics.start("prediction_lock");
        try {
            // 1) 调用唯一批量锁定入口，时间边界由 PostgreSQL 判断。
            log.info("event=prediction_lock_job_started batchSize={}", batchSize);
            PredictionLockResultDto result =
                    predictionLockService.lockDuePredictions(batchSize);

            // 2) 输出批次摘要，单条异常只通过受控 MDC 关联业务记录。
            String status = result.failedCount() == 0 ? "SUCCESS" : "PARTIAL";
            log.info(
                    "event=prediction_lock_job_finished locked={} failed={} reportedDurationMs={}",
                    result.lockedCount(),
                    result.failedCount(),
                    result.durationMs()
            );
            jobMetrics.record(execution, status);
        } catch (RuntimeException exception) {
            log.error(
                    "event=prediction_lock_job_failed batchSize={} exceptionType={}",
                    batchSize, exception.getClass().getSimpleName()
            );
            jobMetrics.record(execution, "FAILED");
            throw exception;
        }
    }
}
