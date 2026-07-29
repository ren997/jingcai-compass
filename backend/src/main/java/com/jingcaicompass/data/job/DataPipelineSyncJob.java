package com.jingcaicompass.data.job;

import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.system.observability.JobMetrics;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立双源流水线任务；原体彩和亚盘定时任务保持不变。 */
@Component
@ConditionalOnBean(DataPipelineService.class)
@ConditionalOnExpression("${app.tasks.enabled:false} && ${app.tasks.data-pipeline.enabled:false}")
public class DataPipelineSyncJob {

    private static final Logger log = LoggerFactory.getLogger(DataPipelineSyncJob.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final DataPipelineService dataPipelineService;
    private final JobMetrics jobMetrics;

    public DataPipelineSyncJob(DataPipelineService dataPipelineService) {
        this(dataPipelineService, JobMetrics.noop());
    }

    @Autowired
    public DataPipelineSyncJob(DataPipelineService dataPipelineService, JobMetrics jobMetrics) {
        this.dataPipelineService = dataPipelineService;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "#{@syncTaskSchedule.dataPipelineFixedDelayMillis()}",
            initialDelayString = "#{@syncTaskSchedule.dataPipelineInitialDelayMillis()}"
    )
    public void syncTodayPipeline() {
        JobMetrics.JobExecution execution = jobMetrics.start("data_pipeline_sync");
        // 1) 按上海时区确定当前竞彩业务日
        LocalDate businessDate = LocalDate.now(SHANGHAI);
        try {
            log.info("event=job_started businessDate={}", businessDate);
            // 2) 调用唯一的业务日流水线入口
            var result = dataPipelineService.run(businessDate);

            // 3) 输出运行 ID、各阶段计数和覆盖率，供任务监控追踪
            String status = result.status().getCode();
            execution.recordOutcome(status);
            log.info(
                    "event=job_finished businessDate={} status={} sportteryRunId={} "
                            + "asianRunId={} matches={} normalized={} pending={} mappingsConfirmed={} "
                            + "mappingsPending={} snapshots={} coverage={} durationMs={}",
                    businessDate,
                    status,
                    result.sportterySyncRunId(),
                    result.asianOddsSyncRunId(),
                    result.normalization().totalMatchCount(),
                    result.normalization().normalizedMatchCount(),
                    result.normalization().pendingMatchCount(),
                    result.confirmedMappingCount(),
                    result.pendingMappingCount(),
                    result.asianOddsSnapshotInsertCount(),
                    result.coverageRate(),
                    execution.durationMs()
            );
            jobMetrics.record(execution, status);
        } catch (RuntimeException exception) {
            execution.recordOutcome("FAILED");
            log.error("event=job_failed businessDate={} status=FAILED exceptionType={}",
                    businessDate, exception.getClass().getSimpleName());
            jobMetrics.record(execution, "FAILED");
            throw exception;
        }
    }
}
