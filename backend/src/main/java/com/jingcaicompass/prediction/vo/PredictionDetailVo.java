package com.jingcaicompass.prediction.vo;

import java.util.List;

/** 某场比赛全部模型的当前公开预测详情。 */
public record PredictionDetailVo(
        Long matchId,
        List<PredictionModelDetailVo> modelPredictions
) {
    public PredictionDetailVo {
        modelPredictions = List.copyOf(modelPredictions);
    }
}
