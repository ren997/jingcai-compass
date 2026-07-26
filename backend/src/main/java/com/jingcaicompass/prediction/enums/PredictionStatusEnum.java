package com.jingcaicompass.prediction.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 预测发布生命周期状态枚举 */
@Getter
public enum PredictionStatusEnum {
    /** 待发布草稿 */
    DRAFT("DRAFT", "草稿"),
    /** 已公开但尚未锁定 */
    PUBLISHED("PUBLISHED", "已发布"),
    /** 已到锁定时间 */
    LOCKED("LOCKED", "已锁定");

    public static final String DESC =
            "预测状态: DRAFT-草稿, PUBLISHED-已发布, LOCKED-已锁定";

    private static final Map<String, PredictionStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(PredictionStatusEnum::getCode, Function.identity()));

    /** 持久化与对外编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    PredictionStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举 */
    public static PredictionStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
