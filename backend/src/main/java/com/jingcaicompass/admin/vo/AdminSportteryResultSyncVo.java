package com.jingcaicompass.admin.vo;

import com.jingcaicompass.data.enums.SyncStatusEnum;
import java.time.LocalDate;

/** 管理员手动赛果同步的安全运行摘要，不含原始载荷。 */
public record AdminSportteryResultSyncVo(
        Long syncRunId,
        LocalDate startDate,
        LocalDate endDate,
        SyncStatusEnum syncStatus,
        int fetchedCount,
        int successCount,
        int failureCount,
        int retryCount,
        int quotaCost,
        int appendedFactCount,
        int supersededFactCount,
        int unchangedFactCount,
        boolean duplicatePayload,
        String errorSummary
) {
}
