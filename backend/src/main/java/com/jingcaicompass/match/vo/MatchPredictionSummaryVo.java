package com.jingcaicompass.match.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.vo.PredictionAsianMarketVo;
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
        AsianHandicapPickEnum asianHandicapPick,
        TotalGoalsPickEnum totalGoalsPick,
        PredictionAsianMarketVo asianMarket,
        PredictionTypeEnum predictionType,
        ConfidenceLevelEnum asianHandicapConfidenceLevel,
        ConfidenceLevelEnum totalGoalsConfidenceLevel,
        ConfidenceLevelEnum confidenceLevel
) {

    /** 兼容缺少亚盘方向字段的历史公开预测摘要。 */
    public MatchPredictionSummaryVo(
            String modelVersion,
            PredictionStatusEnum predictionStatus,
            BigDecimal homeWinProb,
            BigDecimal drawProb,
            BigDecimal awayWinProb,
            HandicapPickEnum handicapPick,
            BigDecimal expectedTotalGoals,
            ConfidenceLevelEnum confidenceLevel
    ) {
        this(
                modelVersion,
                predictionStatus,
                homeWinProb,
                drawProb,
                awayWinProb,
                handicapPick,
                expectedTotalGoals,
                null,
                null,
                null,
                PredictionTypeEnum.SPORTTERY,
                null,
                null,
                confidenceLevel
        );
    }
}
