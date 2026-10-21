package com.jingcaicompass.statistics.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** 概率指标没有可计算样本时的原因。 */
@Getter
public enum ProbabilityMetricUnavailableReasonEnum {
    NO_FINAL_SAMPLE("NO_FINAL_SAMPLE", "没有当前最终赛果样本"),
    NO_SETTLED_ASIAN_SAMPLE("NO_SETTLED_ASIAN_SAMPLE", "没有已结算的亚盘方向样本"),
    MISSING_ASIAN_MODEL_PROBABILITY("MISSING_ASIAN_MODEL_PROBABILITY", "亚盘预测缺少模型/市场概率");

    @JsonValue
    private final String code;
    private final String desc;

    ProbabilityMetricUnavailableReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
