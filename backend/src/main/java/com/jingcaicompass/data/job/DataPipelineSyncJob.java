package com.jingcaicompass.data.job;

import com.jingcaicompass.data.service.DataPipelineService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public DataPipelineSyncJob(DataPipelineService dataPipelineService) {
        this.dataPipelineService = dataPipelineService;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.data-pipeline.fixed-delay}",
            initialDelayString = "${app.tasks.data-pipeline.initial-delay}"
    )
    public void syncTodayPipeline() {
        LocalDate businessDate = LocalDate.now(SHANGHAI);
        log.info("data pipeline job started businessDate={}", businessDate);
        var result = dataPipelineService.run(businessDate);
        log.info(
                "data pipeline job finished businessDate={} status={} sportteryRunId={} "
                        + "asianRunId={} matches={} normalized={} pending={} mappingsConfirmed={} "
                        + "mappingsPending={} snapshots={} coverage={}",
                businessDate,
                result.status(),
                result.sportterySyncRunId(),
                result.asianOddsSyncRunId(),
                result.normalization().totalMatchCount(),
                result.normalization().normalizedMatchCount(),
                result.normalization().pendingMatchCount(),
                result.confirmedMappingCount(),
                result.pendingMappingCount(),
                result.asianOddsSnapshotInsertCount(),
                result.coverageRate()
        );
    }
}
