package com.jingcaicompass.backtest.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * 一场回测样本的赛果状态。
 *
 * <p>只有三个值，与 {@code settlements.settlement_status} 刻意不同：这里是「这行怎么算收益」的
 * 三种情形，市场级输赢另有两列。{@code VOID} 要求两个市场都是 {@code VOID} 且收益为 0，
 * 这是「比赛作废」而不是「盘口走盘」——走盘在亚盘里是常态（四分之一盘打平），
 * 它属于 {@code FINAL} 下的 {@code PUSH}。</p>
 */
@Getter
public enum BacktestResultStatusEnum {
    PENDING("PENDING", "待结算"),
    VOID("VOID", "比赛作废"),
    FINAL("FINAL", "已结算");

    public static final String DESC = "回测样本赛果状态: PENDING-待结算, VOID-比赛作废, FINAL-已结算";

    private static final Map<String, BacktestResultStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(BacktestResultStatusEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    BacktestResultStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static BacktestResultStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
