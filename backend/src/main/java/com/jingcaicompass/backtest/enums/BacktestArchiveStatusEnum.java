package com.jingcaicompass.backtest.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * 一次历史赔率补采的结论。
 *
 * <p><b>这五个值不是「成功/失败」两档，而是「还要不要再采一次」的三档</b>：</p>
 * <ul>
 *   <li>{@code SUCCESS} / {@code NO_HISTORY} / {@code NO_QUOTE_YET} 是<b>终局结论</b>，
 *       重采不会有不同结果，因此可以被复用（负缓存）；</li>
 *   <li>{@code RETRYABLE_FAILURE} 是<b>结构性失败</b>（超时、5xx、限速），下次运行应当重采；</li>
 *   <li>{@code PERMANENT_FAILURE} 是<b>参数或凭据问题</b>（401/403/参数非法），
 *       重采只会把同一个错误刷 N 遍，应直接把 run 判失败而不是逐场重试。</li>
 * </ul>
 *
 * <p>{@code NO_HISTORY} 与 {@code NO_QUOTE_YET} 必须分开：前者是「上游根本没有这场的历史」
 * （404，通常是赛程来自别的源或赛事太老），后者是「有这场但序列里的点太少/为空」。
 * 混成一个值会让「回溯深度不够」和「这场确实没盘口」在报表里无从区分。</p>
 */
@Getter
public enum BacktestArchiveStatusEnum {
    SUCCESS("SUCCESS", "已归档"),
    NO_HISTORY("NO_HISTORY", "上游无该场历史（404）"),
    NO_QUOTE_YET("NO_QUOTE_YET", "序列为空或无可用变快点"),
    RETRYABLE_FAILURE("RETRYABLE_FAILURE", "可重试失败"),
    PERMANENT_FAILURE("PERMANENT_FAILURE", "不可重试失败");

    public static final String DESC =
            "历史赔率补采结论: SUCCESS-已归档, NO_HISTORY-上游无该场历史(404), "
                    + "NO_QUOTE_YET-序列为空或无可用变快点, RETRYABLE_FAILURE-可重试失败, "
                    + "PERMANENT_FAILURE-不可重试失败";

    private static final Map<String, BacktestArchiveStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(BacktestArchiveStatusEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    BacktestArchiveStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static BacktestArchiveStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }

    /** 结论已终局，重采不会有不同结果，可以直接复用（含负缓存）。 */
    public boolean isFinal() {
        return this == SUCCESS || this == NO_HISTORY || this == NO_QUOTE_YET;
    }

    /** 值得再采一次。{@link #PERMANENT_FAILURE} 不在内：那是凭据/参数问题。 */
    public boolean isRetryable() {
        return this == RETRYABLE_FAILURE;
    }
}
