package com.jingcaicompass.backtest.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * 回测运行状态。
 *
 * <p>顺序即语义：{@code ARCHIVING → EVALUATING → SETTLING → AGGREGATING} 是四个真实阶段，
 * 每个阶段失败都可落到 {@code FAILED}；有补采失败但样本可用则落 {@code PARTIAL}。
 * 状态机白名单在 {@code guard_backtest_runs()} 里，Java 侧只负责不越界。</p>
 *
 * <p>{@code INTERRUPTED} 是<b>进程崩溃的痕迹</b>而不是一次正常结束：只有启动自愈把它打上，
 * 且可以续跑回 {@code QUEUED}。把它和 {@code FAILED} 混在一起会让「服务重启」看起来像
 * 「这次实验失败」，进而诱导人重跑一遍、白烧额度。</p>
 */
@Getter
public enum BacktestRunStatusEnum {
    QUEUED("QUEUED", "排队中"),
    ARCHIVING("ARCHIVING", "补采历史赔率"),
    EVALUATING("EVALUATING", "逐步判定"),
    SETTLING("SETTLING", "结算"),
    AGGREGATING("AGGREGATING", "汇总指标"),
    SUCCEEDED("SUCCEEDED", "成功"),
    PARTIAL("PARTIAL", "部分成功（有补采失败但样本可用）"),
    FAILED("FAILED", "失败"),
    CANCELLED("CANCELLED", "已取消"),
    INTERRUPTED("INTERRUPTED", "进程中断，可续跑");

    public static final String DESC =
            "回测运行状态: QUEUED-排队, ARCHIVING-补采, EVALUATING-判定, SETTLING-结算, "
                    + "AGGREGATING-汇总, SUCCEEDED-成功, PARTIAL-部分成功, FAILED-失败, "
                    + "CANCELLED-已取消, INTERRUPTED-中断可续跑";

    /** 终态：不能再改，要改只能新建 run。 */
    private static final Set<BacktestRunStatusEnum> TERMINAL =
            Set.of(SUCCEEDED, PARTIAL, FAILED, CANCELLED);

    /** 非终态，启动自愈会把这些置为 {@code INTERRUPTED}。 */
    private static final Set<BacktestRunStatusEnum> IN_FLIGHT =
            Set.of(QUEUED, ARCHIVING, EVALUATING, SETTLING, AGGREGATING);

    private static final Map<String, BacktestRunStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(BacktestRunStatusEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    BacktestRunStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static BacktestRunStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isInFlight() {
        return IN_FLIGHT.contains(this);
    }

    /** 还在跑，因此可以响应取消请求。 */
    public boolean isCancellable() {
        return isInFlight();
    }
}
