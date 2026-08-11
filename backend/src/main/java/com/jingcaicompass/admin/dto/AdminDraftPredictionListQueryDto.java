package com.jingcaicompass.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 管理员草稿预测分页查询条件。 */
public record AdminDraftPredictionListQueryDto(
        /** 竞彩业务日，留空时查询全部草稿。 */
        LocalDate lotteryDate,
        /** 精确模型版本，留空时不过滤。 */
        @Size(max = 128)
        String modelVersion,
        @Min(1)
        Integer pageNo,
        @Min(1)
        @Max(200)
        Integer pageSize
) {
}
