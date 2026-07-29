package com.jingcaicompass.match.job;

import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.system.observability.JobMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 体彩比赛池定时同步；双开关关闭时不注册。
 */
@Component
@ConditionalOnBean(SportteryPoolSyncService.class)
@ConditionalOnExpression("${app.tasks.enabled:false} && ${app.tasks.sporttery-pool.enabled:false}")
public class SportteryPoolSyncJob {

    private static final Logger log = LoggerFactory.getLogger(SportteryPoolSyncJob.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final SportteryPoolSyncService sportteryPoolSyncService;
    private final JobMetrics jobMetrics;

    public SportteryPoolSyncJob(SportteryPoolSyncService sportteryPoolSyncService) {
        this(sportteryPoolSyncService, JobMetrics.noop());
    }

    @Autowired
    public SportteryPoolSyncJob(SportteryPoolSyncService sportteryPoolSyncService, JobMetrics jobMetrics) {
        this.sportteryPoolSyncService = sportteryPoolSyncService;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.sporttery-pool.fixed-delay}",
            initialDelayString = "${app.tasks.sporttery-pool.initial-delay}"
    )
    public void syncTodayPool() {
        JobMetrics.JobExecution execution = jobMetrics.start("sporttery_pool_sync");
        LocalDate businessDate = LocalDate.now(SHANGHAI);
        try {
            log.info("event=job_started businessDate={}", businessDate);
            var result = sportteryPoolSyncService.sync(new SportteryPoolSyncRequestDto(businessDate));
            String status = result.outcome().status().getCode();
            execution.recordOutcome(status);
            log.info("event=job_finished businessDate={} status={} matches={} snapshots={} durationMs={}",
                    businessDate, status, result.matchUpsertCount(), result.snapshotInsertCount(), execution.durationMs());
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
