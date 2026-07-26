package com.jingcaicompass.prediction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 单条预测发布请求。
 *
 * @param predictionId T302 已导入的 DRAFT 预测 ID
 */
public record PredictionPublishDto(
        @NotNull
        @Positive
        Long predictionId
) {
}
