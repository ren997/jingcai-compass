package com.jingcaicompass.data.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 数据同步运行状态枚举 */
@Getter
public enum SyncStatusEnum {
    RUNNING("RUNNING", "运行中"),
    SUCCESS("SUCCESS", "全部成功"),
    FAILED("FAILED", "失败"),
    PARTIAL("PARTIAL", "部分成功");

    public static final String DESC =
            "同步状态: RUNNING-运行中, SUCCESS-全部成功, FAILED-失败, PARTIAL-部分成功";

    private static final Map<String, SyncStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(SyncStatusEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    SyncStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SyncStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
