package com.jingcaicompass.data.dto;

import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.SyncStatusEnum;

/**
 * Provider 同步模板执行结果。
 */
public record ProviderSyncOutcome(
        /** 同步运行记录。 */
        DataSyncRun syncRun,
        /** 原始响应，请求失败时可能为空。 */
        RawDataPayload payload,
        /** 终态。 */
        SyncStatusEnum status,
        /** 是否命中重复原始响应。 */
        boolean duplicatePayload
) {
}
