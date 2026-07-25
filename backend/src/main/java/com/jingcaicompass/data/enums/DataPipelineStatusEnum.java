package com.jingcaicompass.data.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** 双源数据流水线执行状态。 */
@Getter
public enum DataPipelineStatusEnum {
    SUCCESS("SUCCESS"),
    PARTIAL("PARTIAL"),
    FAILED("FAILED");

    @JsonValue
    private final String code;

    DataPipelineStatusEnum(String code) {
        this.code = code;
    }
}
