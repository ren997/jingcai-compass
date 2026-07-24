package com.jingcaicompass.match.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 供应商实体映射确认状态 */
@Getter
public enum MappingStatusEnum {
    PENDING("PENDING", "待确认"),
    AUTO_CONFIRMED("AUTO_CONFIRMED", "自动确认"),
    MANUAL_CONFIRMED("MANUAL_CONFIRMED", "人工确认"),
    REJECTED("REJECTED", "已拒绝");

    public static final String DESC =
            "映射状态: PENDING-待确认, AUTO_CONFIRMED-自动确认, MANUAL_CONFIRMED-人工确认, REJECTED-已拒绝";

    private static final Map<String, MappingStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(MappingStatusEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    MappingStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static MappingStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
