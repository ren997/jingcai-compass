package com.jingcaicompass.backtest.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * 回测运行的宏观阶段。
 *
 * <p>与 {@link BacktestRunStatusEnum} 分开是因为两者的变化频率差一个量级：状态只在阶段边界、
 * 失败与取消时变，而阶段要随每场比赛刷新一次进度。合成一列会让「进度」与「结论」抢同一行锁。</p>
 */
@Getter
public enum BacktestStageEnum {
    NONE("NONE", "未开始"),
    ARCHIVING("ARCHIVING", "补采历史赔率"),
    EVALUATING("EVALUATING", "逐步判定"),
    SETTLING("SETTLING", "结算"),
    AGGREGATING("AGGREGATING", "汇总指标"),
    DONE("DONE", "已结束");

    public static final String DESC =
            "回测阶段: NONE-未开始, ARCHIVING-补采, EVALUATING-判定, SETTLING-结算, "
                    + "AGGREGATING-汇总, DONE-已结束";

    private static final Map<String, BacktestStageEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(BacktestStageEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    BacktestStageEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static BacktestStageEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }

    /**
     * 由 {@link BacktestRunStatusEnum} 推出应有的阶段。
     *
     * <p>不另存一份「状态 → 阶段」映射表：两套枚举各自维护映射，迟早会有一处忘记同步，
     * 表现是进度条停在上一阶段而状态已经前进——排障时最容易被当成任务卡死的假象。</p>
     */
    public static BacktestStageEnum of(BacktestRunStatusEnum status) {
        return switch (status) {
            case QUEUED -> NONE;
            case ARCHIVING -> ARCHIVING;
            case EVALUATING -> EVALUATING;
            case SETTLING -> SETTLING;
            case AGGREGATING -> AGGREGATING;
            case SUCCEEDED, PARTIAL, FAILED, CANCELLED, INTERRUPTED -> DONE;
        };
    }
}
