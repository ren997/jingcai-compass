package com.jingcaicompass.data.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** Provider 原始数据类型枚举 */
@Getter
public enum ProviderDataTypeEnum {
    SPORTTERY_POOL("SPORTTERY_POOL", "体彩比赛池"),
    SPORTTERY_RESULT("SPORTTERY_RESULT", "体彩赛果"),
    ASIAN_ODDS("ASIAN_ODDS", "亚盘赔率"),
    OTHER("OTHER", "其他原始数据");

    public static final String DESC =
            "原始数据类型: SPORTTERY_POOL-体彩比赛池, SPORTTERY_RESULT-体彩赛果, ASIAN_ODDS-亚盘赔率, OTHER-其他原始数据";

    private static final Map<String, ProviderDataTypeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(ProviderDataTypeEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    ProviderDataTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ProviderDataTypeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
