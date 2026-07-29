package com.jingcaicompass.snapshot.job;

import com.jingcaicompass.snapshot.dto.PredictionSnapshotResultDto;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
import com.jingcaicompass.system.observability.JobMetrics;
import com.jingcaicompass.system.observability.MdcScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时按竞彩业务日发布发生变化的当前预测快照。 */
@Component
@ConditionalOnBean(PredictionSnapshotService.class)
@ConditionalOnExpression(
        "${app.tasks.enabled:false} && ${app.tasks.snapshot-publish.enabled:false}"
)
public class SnapshotPublishJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPublishJob.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final PredictionSnapshotService snapshotService;
    private final Clock clock;
    private final JobMetrics jobMetrics;

    public SnapshotPublishJob(
            PredictionSnapshotService snapshotService,
            Clock clock,
            JobMetrics jobMetrics
    ) {
        this.snapshotService = snapshotService;
        this.clock = clock;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "#{@syncTaskSchedule.snapshotPublishFixedDelayMillis()}",
            initialDelayString = "#{@syncTaskSchedule.snapshotPublishInitialDelayMillis()}"
    )
    public void publishCurrentBusinessDate() {
        JobMetrics.JobExecution execution = jobMetrics.start("snapshot_publish");
        // 1) 使用注入时钟按上海时区确定当前竞彩业务日
        LocalDate businessDate = LocalDate.now(clock.withZone(SHANGHAI));
        log.info("event=prediction_snapshot_job_started businessDate={}", businessDate);

        try {
            // 2) 调用唯一发布入口，由 Service 处理多实例串行和无变化复用
            PredictionSnapshotResultDto result = snapshotService.publish(businessDate);

            // 3) 输出低基数状态和计数，对象路径与失败原因均不进入日志。
            try (MdcScope ignored = MdcScope.snapshot(result.snapshotId())) {
                log.info(
                        "event=prediction_snapshot_job_finished businessDate={} version={} status={} predictions={} reused={}",
                        businessDate,
                        result.snapshotVersion(),
                        result.snapshotStatus(),
                        result.predictionCount(),
                        result.reused()
                );
            }
            jobMetrics.record(
                    execution,
                    result.snapshotStatus() == PredictionSnapshotStatusEnum.FAILED ? "FAILED" : "SUCCESS"
            );
        } catch (RuntimeException exception) {
            log.error(
                    "event=prediction_snapshot_job_failed businessDate={} exceptionType={}",
                    businessDate,
                    exception.getClass().getSimpleName()
            );
            jobMetrics.record(execution, "FAILED");
            throw exception;
        }
    }
}
