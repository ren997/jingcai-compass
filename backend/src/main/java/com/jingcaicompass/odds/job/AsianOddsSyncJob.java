package com.jingcaicompass.odds.job;

import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.service.AsianOddsSyncService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AsianOddsSyncJob(AsianOddsSyncService asianOddsSyncService) {
        this.asianOddsSyncService = asianOddsSyncService;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.asian-odds.fixed-delay}",
            initialDelayString = "${app.tasks.asian-odds.initial-delay}"
    )
    public void syncTodayAsianOdds() {
        LocalDate businessDate = LocalDate.now(SHANGHAI);
        log.info("asian odds sync job started businessDate={}", businessDate);
        var result = asianOddsSyncService.sync(new AsianOddsSyncRequestDto(businessDate));
        log.info(
                "asian odds sync job finished businessDate={} quotaBlocked={} status={} snapshots={} coverage={}",
                businessDate,
                result.quotaBlocked(),
                result.outcome() == null ? null : result.outcome().status(),
                result.snapshotInsertCount(),
                result.coverageRate()
        );
    }
}
