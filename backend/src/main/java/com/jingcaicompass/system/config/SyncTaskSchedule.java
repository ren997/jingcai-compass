package com.jingcaicompass.system.config;

import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import org.springframework.stereotype.Component;

/**
 * 将已绑定的 Duration 配置转换为 {@code @Scheduled} 所需的毫秒值，避免注解重复解析简写时长。
 */
@Component("syncTaskSchedule")
public class SyncTaskSchedule {

    private final SyncTaskProperties taskProperties;

    public SyncTaskSchedule(SyncTaskProperties taskProperties) {
        this.taskProperties = taskProperties;
    }

    public long sportteryPoolFixedDelayMillis() {
        return taskProperties.sportteryPool().fixedDelay().toMillis();
    }

    public long sportteryPoolInitialDelayMillis() {
        return taskProperties.sportteryPool().initialDelay().toMillis();
    }

    public long matchResultFixedDelayMillis() {
        return taskProperties.matchResult().fixedDelay().toMillis();
    }

    public long matchResultInitialDelayMillis() {
        return taskProperties.matchResult().initialDelay().toMillis();
    }

    public long asianOddsFixedDelayMillis() {
        return taskProperties.asianOdds().fixedDelay().toMillis();
    }

    public long asianOddsInitialDelayMillis() {
        return taskProperties.asianOdds().initialDelay().toMillis();
    }

    public long dataPipelineFixedDelayMillis() {
        return taskProperties.dataPipeline().fixedDelay().toMillis();
    }

    public long dataPipelineInitialDelayMillis() {
        return taskProperties.dataPipeline().initialDelay().toMillis();
    }

    public long predictionLockFixedDelayMillis() {
        return taskProperties.predictionLock().fixedDelay().toMillis();
    }

    public long predictionLockInitialDelayMillis() {
        return taskProperties.predictionLock().initialDelay().toMillis();
    }

    public long snapshotPublishFixedDelayMillis() {
        return taskProperties.snapshotPublish().fixedDelay().toMillis();
    }

    public long snapshotPublishInitialDelayMillis() {
        return taskProperties.snapshotPublish().initialDelay().toMillis();
    }

    public long settlementFixedDelayMillis() {
        return taskProperties.settlement().fixedDelay().toMillis();
    }

    public long settlementInitialDelayMillis() {
        return taskProperties.settlement().initialDelay().toMillis();
    }
}
