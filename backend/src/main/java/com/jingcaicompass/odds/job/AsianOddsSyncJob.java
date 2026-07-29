package com.jingcaicompass.odds.job;

import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.service.AsianOddsSyncService;
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

/** 亚盘赛前盘口定时同步；双开关关闭时不注册。 */
@Component
@ConditionalOnBean(AsianOddsSyncService.class)
@ConditionalOnExpression("${app.tasks.enabled:false} && ${app.tasks.asian-odds.enabled:false}")
public class AsianOddsSyncJob {

    private static final Logger log = LoggerFactory.getLogger(AsianOddsSyncJob.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AsianOddsSyncService asianOddsSyncService;
    private final JobMetrics jobMetrics;

    public AsianOddsSyncJob(AsianOddsSyncService asianOddsSyncService) {
        this(asianOddsSyncService, JobMetrics.noop());
    }

    @Autowired
    public AsianOddsSyncJob(AsianOddsSyncService asianOddsSyncService, JobMetrics jobMetrics) {
        this.asianOddsSyncService = asianOddsSyncService;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.asian-odds.fixed-delay}",
            initialDelayString = "${app.tasks.asian-odds.initial-delay}"
    )
    public void syncTodayAsianOdds() {
        JobMetrics.JobExecution execution = jobMetrics.start("asian_odds_sync");
        LocalDate businessDate = LocalDate.now(SHANGHAI);
        try {
            log.info("event=job_started businessDate={}", businessDate);
            var result = asianOddsSyncService.sync(new AsianOddsSyncRequestDto(businessDate));
            String status = result.quotaBlocked()
                    ? "QUOTA_BLOCKED"
                    : result.outcome() == null ? "FAILED" : result.outcome().status().getCode();
            execution.recordOutcome(status);
            log.info("event=job_finished businessDate={} status={} snapshots={} coverage={} durationMs={}",
                    businessDate, status, result.snapshotInsertCount(), result.coverageRate(), execution.durationMs());
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
