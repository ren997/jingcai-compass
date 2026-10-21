package com.jingcaicompass.statistics.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** ROI 缺失口径的结构化原因。 */
@Getter
public enum RoiUnavailableReasonEnum {
    MISSING_FIXED_BETTING_RULE("MISSING_FIXED_BETTING_RULE", "缺少冻结的固定下注规则"),
    MISSING_LOCKED_BETTING_MARKET("MISSING_LOCKED_BETTING_MARKET", "缺少冻结的下注市场选择"),
    MISSING_LOCKED_ODDS_INPUT("MISSING_LOCKED_ODDS_INPUT", "缺少锁定时点赔率输入"),
    NO_SETTLED_SETTLEMENT("NO_SETTLED_SETTLEMENT", "没有已结算的亚盘方向"),
    MISSING_SETTLEMENT_PROFIT("MISSING_SETTLEMENT_PROFIT", "已结算亚盘行缺少收益列");

    @JsonValue
    private final String code;
    private final String desc;

    RoiUnavailableReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
