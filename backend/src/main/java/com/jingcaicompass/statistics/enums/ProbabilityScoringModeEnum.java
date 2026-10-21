package com.jingcaicompass.statistics.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 概率指标的评分口径。
 *
 * <p>两种口径的样本单位不同，不可横向比较：{@code THREE_WAY} 以「一条预测」为样本，
 * 对胜/平/负三路概率求和；{@code ASIAN_DIRECTION} 以「一条预测的一个有方向市场」为样本，
 * 只评该方向本身的二分类概率。
 */
@Getter
public enum ProbabilityScoringModeEnum {
    THREE_WAY("THREE_WAY", "三分类（胜平负）"),
    ASIAN_DIRECTION("ASIAN_DIRECTION", "二分类（亚盘方向）");

    @JsonValue
    private final String code;
    private final String desc;

    ProbabilityScoringModeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
