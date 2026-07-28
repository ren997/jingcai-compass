package com.jingcaicompass.admin.dto;

import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import java.util.List;

/** 后台同步运行分页筛选。 */
public record AdminSyncRunListQueryDto(
        String providerCode,
        ProviderDataTypeEnum dataType,
        List<SyncStatusEnum> syncStatuses,
        Integer pageNo,
        Integer pageSize
) {
}
