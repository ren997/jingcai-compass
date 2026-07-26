package com.jingcaicompass.prediction.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 模型预测置信等级枚举 */
@Getter
public enum ConfidenceLevelEnum {
    LOW("LOW", "低"),
    MEDIUM("MEDIUM", "中"),
    HIGH("HIGH", "高");

    public static final String DESC =
            "预测置信等级: LOW-低, MEDIUM-中, HIGH-高";

    private static final Map<String, ConfidenceLevelEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(ConfidenceLevelEnum::getCode, Function.identity()));

    /** 持久化与对外编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    ConfidenceLevelEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举 */
    public static ConfidenceLevelEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
