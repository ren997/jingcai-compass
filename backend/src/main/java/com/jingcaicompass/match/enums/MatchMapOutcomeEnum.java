package com.jingcaicompass.match.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 比赛自动映射解析结果类型 */
@Getter
public enum MatchMapOutcomeEnum {
    AUTO_CONFIRMED("AUTO_CONFIRMED", "高置信自动确认"),
    PENDING("PENDING", "待人工复核"),
    REUSED("REUSED", "复用已确认映射"),
    NO_CANDIDATE("NO_CANDIDATE", "无可用候选");

    public static final String DESC =
            "比赛映射结果: AUTO_CONFIRMED-自动确认, PENDING-待复核, REUSED-复用已确认, NO_CANDIDATE-无候选";

    private static final Map<String, MatchMapOutcomeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(MatchMapOutcomeEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    MatchMapOutcomeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static MatchMapOutcomeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
