package com.jingcaicompass.admin.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 管理员结算状态的稳定、可由事实重建的诊断码。
 *
 * <p>诊断码与市场解耦：具体缺哪个市场由 {@code AdminStatusDiagnosticVo.marketType} 承载。
 * 按市场扩码会让枚举随市场数量线性膨胀，并且逼着 SQL 与枚举继续互相硬编码——
 * 那正是「亚盘预测永远显示 HAD 待结算」的成因。
 */
@Getter
public enum AdminSettlementDiagnosticEnum {
    AWAITING_RESULT("AWAITING_RESULT", "当前官方赛果尚未确认"),
    SETTLEMENT_MISSING("SETTLEMENT_MISSING", "当前结算缺失"),
    SETTLEMENT_STALE("SETTLEMENT_STALE", "当前结算引用已替代赛果");

    @JsonValue
    private final String code;
    private final String desc;

    AdminSettlementDiagnosticEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
