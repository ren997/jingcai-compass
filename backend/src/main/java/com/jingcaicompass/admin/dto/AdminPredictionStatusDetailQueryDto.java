package com.jingcaicompass.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 读取单条运营预测及其事实、结算版本链。 */
public record AdminPredictionStatusDetailQueryDto(
        @NotNull @Positive Long predictionId
) {
}
