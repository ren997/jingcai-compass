package com.jingcaicompass.admin.mapper;

import java.time.LocalDate;

/** 管理员草稿预测分页 SQL 的已规范化条件。 */
public record AdminDraftPredictionCriteria(
        LocalDate lotteryDate,
        String modelVersion,
        int pageSize,
        long offset
) {
}
