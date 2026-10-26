package com.jingcaicompass.backtest.job;

import com.jingcaicompass.backtest.BacktestSchedule;
import com.jingcaicompass.backtest.service.BacktestRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时消费回测队列。
 *
 * <p>只做一件事：取一个 {@code QUEUED} 的 run 并跑完。失败与重试语义都在
 * {@link BacktestRunnerService} 里，因为「一个 run 跑多久、怎么中断、怎么续跑」
 * 需要脱离调度周期单独验证。</p>
 *
 * <p><b>不加 {@code @Component}</b>：由 {@code PersistenceServicesAutoConfiguration}
 * 以 {@code @Bean + @ConditionalOnProperty} 提供，与其余八个任务一致。
 * 这里踩过坑——{@code @ConditionalOnBean} 在组件扫描阶段对配置类里的 {@code @Bean} 恒为 false，
 * 会让任务静默地从未创建，表现是「队列里一直有 QUEUED，但什么都不发生」。</p>
 *
 * <p><b>异常必须自己吞掉</b>：{@code @Scheduled} 抛出的异常会被 Spring 记一条错误日志后继续调度，
 * 但如果这里不 catch，{@code TaskScheduler} 的单线程池会把后续任务排在异常之后——
 * 一次数据库瞬断就能让所有定时任务停摆一轮。</p>
 */
public class BacktestRunJob {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunJob.class);

    private final BacktestRunnerService runnerService;

    public BacktestRunJob(BacktestRunnerService runnerService) {
        this.runnerService = runnerService;
    }

    /**
     * 每次执行最多消费一个 run。
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：回测可能跑几十分钟，
     * {@code fixedRate} 会在它还没结束时继续触发，把一个单线程池排满。</p>
     *
     * <p>两个延迟都走 SpEL 取毫秒，不写 {@code 30s} / {@code 60s} 这类简写：
     * {@code initialDelayString} 不接受简写时长，会在创建 bean 阶段抛
     * {@code NumberFormatException} 让应用起不来（见 {@link BacktestSchedule}）。</p>
     */
    @Scheduled(
            fixedDelayString = "#{@backtestSchedule.pollIntervalMillis()}",
            initialDelayString = "#{@backtestSchedule.initialDelayMillis()}"
    )
    public void poll() {
        try {
            boolean ran = runnerService.pollOnce();
            if (ran) {
                log.info("event=backtest_run_job_finished");
            }
        } catch (RuntimeException exception) {
            log.error("event=backtest_run_job_failed", exception);
        }
    }
}
