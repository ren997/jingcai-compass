package com.jingcaicompass.admin.dto;

import java.time.LocalDate;

/** 管理员手动同步体彩赛果的连续竞彩业务日范围。 */
public record AdminSportteryResultSyncDto(
        LocalDate startDate,
        LocalDate endDate
) {
}
