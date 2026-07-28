package com.jingcaicompass.admin.vo;

import com.jingcaicompass.data.enums.ProviderDataTypeEnum;

/** 指定业务日内某 Provider 数据类型的消耗额度，不表示剩余或总额度。 */
public record AdminSyncRunQuotaItemVo(
        String providerCode,
        ProviderDataTypeEnum dataType,
        long runCount,
        long consumedQuota,
        Integer warningThreshold,
        boolean warningTriggered
) {
}
