package com.jingcaicompass.backtest.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jingcaicompass.backtest.BacktestProperties;
import com.jingcaicompass.backtest.BacktestSchedule;
import com.jingcaicompass.backtest.service.BacktestRunnerService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class BacktestRunJobTest {

    @Test
    @DisplayName("调度参数取 SpEL 毫秒，不写简写时长")
    void usesMillisSpelInsteadOfShorthandDuration() throws NoSuchMethodException {
        Scheduled scheduled = BacktestRunJob.class.getMethod("poll").getAnnotation(Scheduled.class);

        // initialDelayString 不认 Spring Boot 的简写时长：写 "60s" 会在创建 bean 阶段抛
        // NumberFormatException，而该 bean 只在 app.tasks.backtest-run.enabled=true 时才创建，
        // 默认配置下永远发现不了。断言写成对注解文本的检查，才能在看不出差别的地方拦住回退。
        assertThat(scheduled.fixedDelayString()).isEqualTo("#{@backtestSchedule.pollIntervalMillis()}");
        assertThat(scheduled.initialDelayString()).isEqualTo("#{@backtestSchedule.initialDelayMillis()}");
        // 顺带挡住「退回 ${...:60s} 占位符写法」——那是本次唯一让应用起不来的形状。
        assertThat(scheduled.initialDelayString()).startsWith("#{@backtestSchedule.");
    }

    @Test
    @DisplayName("调度失败只记日志，不把异常抛回调度线程")
    void swallowsRunnerFailure() {
        BacktestRunnerService runnerService = mock(BacktestRunnerService.class);
        when(runnerService.pollOnce()).thenThrow(new IllegalStateException("db down"));

        // 抛出去虽然不致命，但会把单线程池的后续任务排在异常之后，
        // 一次数据库瞬断就能让所有定时任务停摆一轮。
        new BacktestRunJob(runnerService).poll();
    }

    @Test
    @DisplayName("毫秒换算与 Duration 配置一一对应")
    void convertsDurationsToMillis() {
        BacktestProperties properties = new BacktestProperties(
                "NOVA_API",
                105,
                BacktestProperties.Limits.defaults(),
                new BacktestProperties.Worker(
                        Duration.ofSeconds(45), Duration.ofSeconds(90), Duration.ofMinutes(5), 50));
        BacktestSchedule schedule = new BacktestSchedule(properties);

        assertThat(schedule.pollIntervalMillis()).isEqualTo(45_000L);
        assertThat(schedule.initialDelayMillis()).isEqualTo(90_000L);
    }

    @Test
    @DisplayName("未配置 worker 时用 30s / 60s 的毫秒等价值")
    void fallsBackToDefaultWorkerDelays() {
        BacktestProperties properties = BacktestProperties.defaults();
        BacktestSchedule schedule = new BacktestSchedule(properties);

        assertThat(schedule.pollIntervalMillis()).isEqualTo(30_000L);
        assertThat(schedule.initialDelayMillis()).isEqualTo(60_000L);
    }
}
