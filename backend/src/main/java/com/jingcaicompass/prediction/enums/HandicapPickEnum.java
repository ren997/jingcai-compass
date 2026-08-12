package com.jingcaicompass.prediction.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 体彩让球胜平负倾向枚举；结果必须结合体彩官方让球线解读。 */
@Getter
public enum HandicapPickEnum {
    HOME_WIN("HOME_WIN", "让球胜"),
    DRAW("DRAW", "让球平"),
    AWAY_WIN("AWAY_WIN", "让球负");

    public static final String DESC =
            "体彩让球胜平负倾向: HOME_WIN-让球胜, DRAW-让球平, AWAY_WIN-让球负";

    private static final Map<String, HandicapPickEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(HandicapPickEnum::getCode, Function.identity()));

    /** 持久化与对外编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    HandicapPickEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举 */
    public static HandicapPickEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
