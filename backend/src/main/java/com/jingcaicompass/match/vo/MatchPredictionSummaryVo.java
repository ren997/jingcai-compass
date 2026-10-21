package com.jingcaicompass.match.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.vo.PredictionAsianMarketVo;
import com.jingcaicompass.prediction.vo.PredictionAsianModelVo;
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
        ConfidenceLevelEnum confidenceLevel,
        /** 亚盘模型概率十列；为 null 表示这一版没有模型概率（体彩 / V24 之前）。 */
        PredictionAsianModelVo asianModel
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
                confidenceLevel,
                null
        );
    }
}
