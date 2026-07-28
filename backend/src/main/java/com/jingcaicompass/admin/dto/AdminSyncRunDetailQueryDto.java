package com.jingcaicompass.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 后台同步运行详情查询。 */
public record AdminSyncRunDetailQueryDto(
        @NotNull @Positive Long syncRunId
) {
}
