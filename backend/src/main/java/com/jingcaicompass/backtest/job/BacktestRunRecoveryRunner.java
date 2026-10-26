package com.jingcaicompass.backtest.job;

import com.jingcaicompass.backtest.service.BacktestRunnerService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 启动时把心跳超时的回测标为 {@code INTERRUPTED}。
 *
 * <p>放在 {@link ApplicationRunner} 而不是 {@code @Scheduled}：调度线程池默认单线程，
 * 一个卡住的回测就会把恢复任务排到它后面——最需要恢复的时候恰好不执行。
 * 这与 {@code SyncRunRecoveryRunner} 是同一个理由，区别在于那次只能把记录改成 {@code FAILED}
 * （同步任务没有断点），而回测有可续跑的状态，所以这里标 {@code INTERRUPTED} 并在管理端提供续跑。</p>
 *
 * <p>失败必须吞掉：恢复只是让管理端显示正确，不该因为一条 UPDATE 失败就阻止应用启动。</p>
 */
@Component
@ConditionalOnBean(DataSource.class)
public class BacktestRunRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunRecoveryRunner.class);

    private final BacktestRunnerService runnerService;

    public BacktestRunRecoveryRunner(BacktestRunnerService runnerService) {
        this.runnerService = runnerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int recovered = runnerService.recoverStaleRuns();
            log.info("event=backtest_run_recovery_finished recovered={}", recovered);
        } catch (RuntimeException exception) {
            log.error("event=backtest_run_recovery_failed", exception);
        }
    }
}
