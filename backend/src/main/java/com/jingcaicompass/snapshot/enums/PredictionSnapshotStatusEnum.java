package com.jingcaicompass.snapshot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 公开预测快照发布状态枚举 */
@Getter
public enum PredictionSnapshotStatusEnum {
    PENDING("PENDING", "待发布"),
    PUBLISHED("PUBLISHED", "已发布"),
    FAILED("FAILED", "发布失败");

    public static final String DESC =
            "预测快照状态: PENDING-待发布, PUBLISHED-已发布, FAILED-发布失败";

    private static final Map<String, PredictionSnapshotStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(PredictionSnapshotStatusEnum::getCode, Function.identity()));

    /** 持久化与对外编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    PredictionSnapshotStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举 */
    public static PredictionSnapshotStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
