package com.jingcaicompass.admin.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 后台同步额度日汇总。 */
public record AdminSyncRunQuotaSummaryVo(
        LocalDate businessDate,
        Instant generatedAt,
        List<AdminSyncRunQuotaItemVo> items
) {
}
