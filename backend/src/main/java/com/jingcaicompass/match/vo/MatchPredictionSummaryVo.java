package com.jingcaicompass.match.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.math.BigDecimal;

/** 公开比赛列表中一个模型的当前已发布预测摘要。 */
public record MatchPredictionSummaryVo(
        String modelVersion,
        PredictionStatusEnum predictionStatus,
        BigDecimal homeWinProb,
        BigDecimal drawProb,
        BigDecimal awayWinProb,
        HandicapPickEnum handicapPick,
        BigDecimal expectedTotalGoals,
        ConfidenceLevelEnum confidenceLevel
) {
}
