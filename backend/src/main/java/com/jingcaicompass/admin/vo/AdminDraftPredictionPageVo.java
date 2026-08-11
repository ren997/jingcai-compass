package com.jingcaicompass.admin.vo;

import java.util.List;

/** 管理员草稿预测稳定分页结果。 */
public record AdminDraftPredictionPageVo(
        List<AdminDraftPredictionListItemVo> records,
        int pageNo,
        int pageSize,
        long total
) {
}
