package com.jingcaicompass.match.job;

import com.jingcaicompass.match.dto.MatchResultSyncRequestDto;
import com.jingcaicompass.match.service.MatchResultSyncService;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import com.jingcaicompass.system.observability.JobMetrics;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 体彩赛果定时补数：按配置的近期开售日范围重复同步。 */
@Component
@ConditionalOnBean({DataSource.class, MatchResultSyncService.class})
@ConditionalOnExpression("${app.tasks.enabled:false} && ${app.tasks.match-result.enabled:false}")
public class MatchResultSyncJob {

    private static final Logger log = LoggerFactory.getLogger(MatchResultSyncJob.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final MatchResultSyncService matchResultSyncService;
    private final SyncTaskProperties taskProperties;
    private final JobMetrics jobMetrics;

    public MatchResultSyncJob(
            MatchResultSyncService matchResultSyncService,
            SyncTaskProperties taskProperties
    ) {
        this(matchResultSyncService, taskProperties, JobMetrics.noop());
    }

    @Autowired
    public MatchResultSyncJob(
            MatchResultSyncService matchResultSyncService,
            SyncTaskProperties taskProperties,
            JobMetrics jobMetrics
    ) {
        this.matchResultSyncService = matchResultSyncService;
        this.taskProperties = taskProperties;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.match-result.fixed-delay}",
            initialDelayString = "${app.tasks.match-result.initial-delay}"
    )
    public void syncRecentResults() {
        JobMetrics.JobExecution execution = jobMetrics.start("match_result_sync");
        // 1) 以上海竞彩日计算包含当天的固定补数窗口。
        LocalDate endDate = LocalDate.now(SHANGHAI);
        int lookbackDays = taskProperties.matchResult().lookbackDays();
        LocalDate startDate = endDate.minusDays(lookbackDays - 1L);
        try {
            log.info("event=job_started startDate={} endDate={}", startDate, endDate);

            // 2) 委派给服务并记录追加、替代和幂等跳过数量。
            var result = matchResultSyncService.sync(new MatchResultSyncRequestDto(startDate, endDate));
            String status = result.outcome().status().getCode();
            execution.recordOutcome(status);
            log.info(
                    "event=job_finished startDate={} endDate={} status={} appended={} superseded={} unchanged={} durationMs={}",
                    startDate,
                    endDate,
                    status,
                    result.appendedFactCount(),
                    result.supersededFactCount(),
                    result.unchangedFactCount(),
                    execution.durationMs()
            );
            jobMetrics.record(execution, status);
        } catch (RuntimeException exception) {
            execution.recordOutcome("FAILED");
            log.error("event=job_failed startDate={} endDate={} status=FAILED exceptionType={}",
                    startDate, endDate, exception.getClass().getSimpleName());
            jobMetrics.record(execution, "FAILED");
            throw exception;
        }
    }
}
