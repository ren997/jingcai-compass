package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.MappingReviewScopeEnum;

/**
 * 映射复核列表查询。
 *
 * @param providerCode 供应商编码，可空
 * @param mappingStatus 状态筛选，可空；默认仅 PENDING
 * @param reviewScope 当前未开赛或已开赛历史范围；默认 ACTIVE
 * @param pageNo 页码，从 1 开始
 * @param pageSize 页大小
 */
public record MappingReviewListQueryDto(
        String providerCode,
        MappingStatusEnum mappingStatus,
        MappingReviewScopeEnum reviewScope,
        Integer pageNo,
        Integer pageSize
) {
}
