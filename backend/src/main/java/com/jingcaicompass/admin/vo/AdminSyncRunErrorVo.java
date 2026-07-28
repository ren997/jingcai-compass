package com.jingcaicompass.admin.vo;

import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import java.time.Instant;

/** 同步失败或部分成功运行的安全错误摘要。 */
public record AdminSyncRunErrorVo(
        Long syncRunId,
        String providerCode,
        ProviderDataTypeEnum dataType,
        SyncStatusEnum syncStatus,
        Instant startedAt,
        Instant finishedAt,
        Integer failureCount,
        Integer retryCount,
        String errorSummary
) {
}
