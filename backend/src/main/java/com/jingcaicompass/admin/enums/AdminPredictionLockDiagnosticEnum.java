package com.jingcaicompass.admin.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** 管理员预测锁定状态的稳定诊断码。 */
@Getter
public enum AdminPredictionLockDiagnosticEnum {
    OVERDUE("OVERDUE", "已到锁定时间但仍未锁定"),
    SCHEDULED("SCHEDULED", "已发布，尚未到锁定时间"),
    LOCKED("LOCKED", "预测已锁定");

    @JsonValue
    private final String code;
    private final String desc;

    AdminPredictionLockDiagnosticEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
