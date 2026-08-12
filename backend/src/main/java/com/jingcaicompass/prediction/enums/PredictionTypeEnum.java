package com.jingcaicompass.prediction.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** 区分历史体彩预测与当前亚盘专用预测的产品类型。 */
@Getter
public enum PredictionTypeEnum {
    SPORTTERY("SPORTTERY", "体彩预测"),
    ASIAN("ASIAN", "亚盘预测");

    public static final String DESC = "预测类型: SPORTTERY-体彩预测, ASIAN-亚盘预测";

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    PredictionTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
