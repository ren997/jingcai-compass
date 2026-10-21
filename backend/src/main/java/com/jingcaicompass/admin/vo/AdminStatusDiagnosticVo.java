package com.jingcaicompass.admin.vo;

import com.jingcaicompass.settlement.enums.MarketTypeEnum;

/**
 * 一条面向后台的稳定状态码及可读说明。
 *
 * <p>{@code marketType} 只在结算诊断上有值；锁定诊断与市场无关，恒为 {@code null}。
 */
public record AdminStatusDiagnosticVo(
        String code,
        String description,
        MarketTypeEnum marketType
) {

    /** 与市场无关的诊断（锁定诊断、等待赛果）。 */
    public AdminStatusDiagnosticVo(String code, String description) {
        this(code, description, null);
    }
}
