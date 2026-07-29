package com.jingcaicompass.admin.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** 管理员结算状态的稳定、可由事实重建的诊断码。 */
@Getter
public enum AdminSettlementDiagnosticEnum {
    AWAITING_RESULT("AWAITING_RESULT", "当前官方赛果尚未确认"),
    SETTLEMENT_MISSING_HAD("SETTLEMENT_MISSING_HAD", "HAD 当前结算缺失"),
    SETTLEMENT_MISSING_HHAD("SETTLEMENT_MISSING_HHAD", "HHAD 当前结算缺失"),
    SETTLEMENT_STALE_HAD("SETTLEMENT_STALE_HAD", "HAD 当前结算引用已替代赛果"),
    SETTLEMENT_STALE_HHAD("SETTLEMENT_STALE_HHAD", "HHAD 当前结算引用已替代赛果");

    @JsonValue
    private final String code;
    private final String desc;

    AdminSettlementDiagnosticEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
