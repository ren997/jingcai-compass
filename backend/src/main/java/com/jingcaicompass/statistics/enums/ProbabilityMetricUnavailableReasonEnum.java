package com.jingcaicompass.statistics.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** 概率指标没有可计算样本时的原因。 */
@Getter
public enum ProbabilityMetricUnavailableReasonEnum {
    NO_FINAL_SAMPLE("NO_FINAL_SAMPLE", "没有当前最终赛果样本");

    @JsonValue
    private final String code;
    private final String desc;

    ProbabilityMetricUnavailableReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
