package com.jingcaicompass.admin.dto;

import java.time.LocalDate;

/** 后台同步额度汇总查询；业务日期为空时使用上海当天。 */
public record AdminSyncRunQuotaSummaryQueryDto(
        LocalDate businessDate
) {
}
