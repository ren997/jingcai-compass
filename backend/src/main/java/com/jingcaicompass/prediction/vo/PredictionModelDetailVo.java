package com.jingcaicompass.prediction.vo;

import java.util.List;

/** 同一比赛和模型的当前公开预测与替代历史。 */
public record PredictionModelDetailVo(
        String modelVersion,
        PredictionVersionVo currentPrediction,
        List<PredictionVersionVo> historicalPredictions
) {
    public PredictionModelDetailVo {
        historicalPredictions = List.copyOf(historicalPredictions);
    }
}
