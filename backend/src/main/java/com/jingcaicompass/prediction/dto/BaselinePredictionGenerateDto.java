package com.jingcaicompass.prediction.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** 可解释预测基线的管理员生成请求。 */
public record BaselinePredictionGenerateDto(
        /** 需要生成预测的竞彩业务日（Asia/Shanghai）。 */
        @NotNull LocalDate lotteryDate
) {
}
