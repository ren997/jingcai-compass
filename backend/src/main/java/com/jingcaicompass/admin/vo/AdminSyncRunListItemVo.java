package com.jingcaicompass.admin.vo;

import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import java.time.Instant;

/** 后台同步运行列表项，不含原始响应。 */
public record AdminSyncRunListItemVo(
        Long syncRunId,
        String providerCode,
        ProviderDataTypeEnum dataType,
        SyncStatusEnum syncStatus,
        Instant startedAt,
        Instant finishedAt,
        Integer fetchedCount,
        Integer successCount,
        Integer failureCount,
        Integer retryCount,
        Integer quotaCost,
        String errorSummary
) {
}
