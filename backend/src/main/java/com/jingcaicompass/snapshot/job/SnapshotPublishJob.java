package com.jingcaicompass.snapshot.job;

import com.jingcaicompass.snapshot.dto.PredictionSnapshotResultDto;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
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

    public SnapshotPublishJob(
            PredictionSnapshotService snapshotService,
            Clock clock
    ) {
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.tasks.snapshot-publish.fixed-delay}",
            initialDelayString = "${app.tasks.snapshot-publish.initial-delay}"
    )
    public void publishCurrentBusinessDate() {
        // 1) 使用注入时钟按上海时区确定当前竞彩业务日
        LocalDate businessDate = LocalDate.now(clock.withZone(SHANGHAI));
        log.info("prediction snapshot job started businessDate={}", businessDate);

        try {
            // 2) 调用唯一发布入口，由 Service 处理多实例串行和无变化复用
            PredictionSnapshotResultDto result = snapshotService.publish(businessDate);

            // 3) 输出低基数状态和计数，对象路径与失败原因留在数据库记录
            log.info(
                    "prediction snapshot job finished businessDate={} snapshotId={} "
                            + "version={} status={} predictions={} reused={}",
                    businessDate,
                    result.snapshotId(),
                    result.snapshotVersion(),
                    result.snapshotStatus(),
                    result.predictionCount(),
                    result.reused()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "prediction snapshot job failed businessDate={}",
                    businessDate,
                    exception
            );
            throw exception;
        }
    }
}
