package com.jingcaicompass.backtest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 历史回测的运行约束。
 *
 * <p>三个数字是硬约束而不是「默认值」，因为越过它们不会立刻报错，只会让回测悄悄变得不可用：</p>
 *
 * <ul>
 *   <li>{@code limits.minLotteryDate}：{@code /nova/tf/odds-history} 只回溯约 4 个月
 *       （实测 2026-06-10 返回 404、06-15 只有 1 个变动点、06-20 起完整）。允许更早的日期，
 *       结果是整批场次归档为 {@code NO_HISTORY}，看起来像「这段时间没有比赛」；</li>
 *   <li>{@code limits.maxSpanDays}：NovaAPI 固定 2s/次的全局限速，跨度直接决定墙钟时间
 *       （120 天 ≈ 1200 场 ≈ 40 分钟纯等待）；</li>
 *   <li>{@code limits.budgetConfirmThreshold}：调用次数就是额度。越过阈值要求二次确认，
 *       是为了让「顺手跑一下全量」变成一次显式决定。</li>
 * </ul>
 *
 * <p>{@code decisionLeadMinutes} 故意不给「相对开赛」以外的语义：赔率在赛前约 1h45m 停更，
 * 因此 105 分钟是能拿到「接近收盘」的最后一个时点。写 0 会取到停盘价，写 360 会取到开盘价，
 * 两者都还是合法回测，但结论不可比——所以它是 run 的定义列，落库后不可改。</p>
 */
@Validated
@ConfigurationProperties("app.backtest")
public record BacktestProperties(
        String providerCode,
        @Min(1) int decisionLeadMinutes,
        @NotNull Limits limits,
        @NotNull Worker worker
) {

    /** 归档来源编码。单源改造后只剩 {@code NOVA_API}，保留配置是为了让 stub 环境的报错更明确。 */
    public static final String DEFAULT_PROVIDER_CODE = "NOVA_API";

    /** 赔率实测在赛前约 105 分钟停更；再往后取到的是停盘价。 */
    public static final int DEFAULT_DECISION_LEAD_MINUTES = 105;

    /** 历史赔率可回溯边界（实测）。 */
    public static final LocalDate DEFAULT_MIN_LOTTERY_DATE = LocalDate.of(2026, 6, 20);

    public BacktestProperties {
        providerCode = (providerCode == null || providerCode.isBlank())
                ? DEFAULT_PROVIDER_CODE
                : providerCode.trim();
        decisionLeadMinutes = decisionLeadMinutes <= 0
                ? DEFAULT_DECISION_LEAD_MINUTES
                : decisionLeadMinutes;
        limits = limits == null ? Limits.defaults() : limits;
        worker = worker == null ? Worker.defaults() : worker;
    }

    /** 未配置时的默认值，供单元测试与非 Spring 装配路径使用。 */
    public static BacktestProperties defaults() {
        return new BacktestProperties(
                DEFAULT_PROVIDER_CODE,
                DEFAULT_DECISION_LEAD_MINUTES,
                Limits.defaults(),
                Worker.defaults()
        );
    }

    /**
     * 创建 run 时的输入边界。
     *
     * @param minLotteryDate         允许的最早业务日（早于它必然无历史赔率）
     * @param maxSpanDays            单次 run 允许的最大日期跨度
     * @param maxParameterSets       单次 run 允许的参数组数量上限
     * @param budgetConfirmThreshold 预估调用次数超过该值需二次确认
     */
    public record Limits(
            LocalDate minLotteryDate,
            @Min(1) int maxSpanDays,
            @Min(1) @Max(8) int maxParameterSets,
            @PositiveOrZero int budgetConfirmThreshold
    ) {

        public Limits {
            minLotteryDate = minLotteryDate == null ? DEFAULT_MIN_LOTTERY_DATE : minLotteryDate;
            maxSpanDays = maxSpanDays <= 0 ? 120 : maxSpanDays;
            maxParameterSets = maxParameterSets <= 0 ? 8 : Math.min(maxParameterSets, 8);
            budgetConfirmThreshold = budgetConfirmThreshold <= 0 ? 4000 : budgetConfirmThreshold;
        }

        public static Limits defaults() {
            return new Limits(DEFAULT_MIN_LOTTERY_DATE, 120, 8, 4000);
        }
    }

    /**
     * worker 的取单与自愈节奏。
     *
     * @param pollInterval     空闲时多久看一次 {@code QUEUED} 队列
     * @param initialDelay     应用启动后首次扫描的延迟；给它留时间让 Flyway 与自愈先跑完
     * @param heartbeatTimeout 心跳超时；超过它的非终态 run 视为已中断
     * @param batchSize        每处理多少场刷一次进度（逐场刷库会把 2000 场变成 2000 次往返）
     */
    public record Worker(
            @NotNull Duration pollInterval,
            Duration initialDelay,
            @NotNull Duration heartbeatTimeout,
            @Min(1) int batchSize
    ) {

        public Worker {
            pollInterval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                    ? Duration.ofSeconds(30)
                    : pollInterval;
            initialDelay = initialDelay == null || initialDelay.isNegative()
                    ? Duration.ofSeconds(60)
                    : initialDelay;
            heartbeatTimeout = heartbeatTimeout == null || heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()
                    ? Duration.ofMinutes(5)
                    : heartbeatTimeout;
            batchSize = batchSize <= 0 ? 50 : batchSize;
        }

        public static Worker defaults() {
            return new Worker(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMinutes(5), 50);
        }
    }
}
