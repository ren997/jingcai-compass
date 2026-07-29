package com.jingcaicompass.admin.vo;

import java.util.List;

/** 后台状态列表的分页数据及可复算待人工处理总数。 */
public record AdminPredictionStatusPageVo(
        List<AdminPredictionStatusItemVo> records,
        long pageNo,
        long pageSize,
        long total,
        long manualAttentionCount
) {
    public AdminPredictionStatusPageVo {
        records = List.copyOf(records);
    }
}
