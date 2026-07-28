package com.jingcaicompass.admin.dto;

import com.jingcaicompass.data.enums.ProviderDataTypeEnum;

/** 后台同步失败/部分成功运行的分页筛选。 */
public record AdminSyncRunErrorQueryDto(
        String providerCode,
        ProviderDataTypeEnum dataType,
        Integer pageNo,
        Integer pageSize
) {
}
