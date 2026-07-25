package com.jingcaicompass.data.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 双源数据流水线执行状态枚举。 */
@Getter
public enum DataPipelineStatusEnum {
    SUCCESS("SUCCESS", "全部阶段成功"),
    PARTIAL("PARTIAL", "部分阶段成功"),
    FAILED("FAILED", "体彩阶段失败");

    public static final String DESC =
            "流水线状态: SUCCESS-全部阶段成功, PARTIAL-部分阶段成功, FAILED-体彩阶段失败";

    private static final Map<String, DataPipelineStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(DataPipelineStatusEnum::getCode, Function.identity()));

    /** 持久化与接口编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 中文描述。 */
    private final String desc;

    DataPipelineStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按业务编码解析，未知编码返回 {@code null}。 */
    public static DataPipelineStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
