package com.jingcaicompass.prediction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 公开比赛预测详情查询条件。 */
public record PredictionDetailQueryDto(
        /** 持久化比赛 ID。 */
        @NotNull @Positive Long matchId
) {
}
