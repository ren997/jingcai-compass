package com.jingcaicompass.backtest;

import org.springframework.stereotype.Component;

/**
 * 把已绑定的 {@link java.time.Duration} 换成 {@code @Scheduled} 需要的毫秒值。
 *
 * <p><b>{@code initialDelayString} 不认 Spring Boot 的简写时长。</b>写
 * {@code initialDelayString = "${...:60s}"} 时，应用会在创建 job bean 阶段直接
 * {@code NumberFormatException: For input string: "60s"} 起不来——而它只在
 * {@code app.tasks.backtest-run.enabled=true} 时才创建，所以默认配置下永远发现不了。
 * {@code fixedDelayString} 与它是两条解析路径，写法一致不代表行为一致。</p>
 *
 * <p>与其余八个 job 保持同一形态（{@code SyncTaskSchedule}）：注解里只出现 SpEL 调用，
 * 毫秒换算收在一处，Duration 的解析差异就不会散落到注解上。</p>
 */
@Component("backtestSchedule")
public class BacktestSchedule {

    private final BacktestProperties properties;

    public BacktestSchedule(BacktestProperties properties) {
        this.properties = properties;
    }

    public long pollIntervalMillis() {
        return properties.worker().pollInterval().toMillis();
    }

    public long initialDelayMillis() {
        return properties.worker().initialDelay().toMillis();
    }
}
