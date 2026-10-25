package com.jingcaicompass.data.job;

import com.jingcaicompass.data.service.SyncRunRecoveryService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 启动时把上一次进程遗留的 RUNNING 同步记录收尾为 FAILED。
 *
 * <p>放在 {@link ApplicationRunner} 而不是定时任务里：定时方法跑在调度线程池上，一个卡住的
 * 同步就会把恢复任务本身排到后面，最需要它的时候恰好不执行。Runner 在 Web 服务器就绪前后、
 * 定时任务的 initial-delay 到点之前同步执行，是唯一确定能先跑起来的位置。</p>
 *
 * <p>失败必须吞掉：恢复是「让管理端显示正确」，不该因为一条 UPDATE 失败就阻止应用启动。</p>
 */
@Component
@ConditionalOnBean(DataSource.class)
public class SyncRunRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncRunRecoveryRunner.class);

    private final SyncRunRecoveryService syncRunRecoveryService;

    @Autowired
    public SyncRunRecoveryRunner(SyncRunRecoveryService syncRunRecoveryService) {
        this.syncRunRecoveryService = syncRunRecoveryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int recovered = syncRunRecoveryService.recoverStaleRuns();
            log.info("event=sync_run_recovery_finished recovered={}", recovered);
        } catch (RuntimeException exception) {
            log.warn("event=sync_run_recovery_failed exceptionType={} error={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }
}
