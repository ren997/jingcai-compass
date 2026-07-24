package com.jingcaicompass.match.job;

import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public SportteryPoolSyncJob(SportteryPoolSyncService sportteryPoolSyncService) {
        this.sportteryPoolSyncService = sportteryPoolSyncService;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.sporttery-pool.fixed-delay}",
            initialDelayString = "${app.tasks.sporttery-pool.initial-delay}"
    )
    public void syncTodayPool() {
        LocalDate businessDate = LocalDate.now(SHANGHAI);
        log.info("sporttery pool sync job started businessDate={}", businessDate);
        var result = sportteryPoolSyncService.sync(new SportteryPoolSyncRequestDto(businessDate));
        log.info(
                "sporttery pool sync job finished businessDate={} status={} matches={} snapshots={}",
                businessDate,
                result.outcome().status(),
                result.matchUpsertCount(),
                result.snapshotInsertCount()
        );
    }
}
