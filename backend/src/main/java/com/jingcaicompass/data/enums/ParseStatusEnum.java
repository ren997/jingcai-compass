package com.jingcaicompass.data.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 原始响应解析状态枚举 */
@Getter
public enum ParseStatusEnum {
    PENDING("PENDING", "待解析"),
    SUCCESS("SUCCESS", "解析成功"),
    FAILED("FAILED", "解析失败");

    public static final String DESC =
            "解析状态: PENDING-待解析, SUCCESS-解析成功, FAILED-解析失败";

    private static final Map<String, ParseStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(ParseStatusEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    ParseStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ParseStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
