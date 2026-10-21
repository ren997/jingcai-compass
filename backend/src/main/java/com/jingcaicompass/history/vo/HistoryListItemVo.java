package com.jingcaicompass.history.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.vo.PredictionAsianModelVo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 一条公开预测版本及其可追溯事实、结算历史。 */
public record HistoryListItemVo(
        Long predictionId,
        Integer predictionVersion,
        String modelVersion,
        String featureVersion,
        PredictionStatusEnum predictionStatus,
        BigDecimal homeWinProb,
        BigDecimal drawProb,
        BigDecimal awayWinProb,
        HandicapPickEnum handicapPick,
        BigDecimal expectedTotalGoals,
        ConfidenceLevelEnum confidenceLevel,
        String analysisSummary,
        String predictionHash,
        Instant generatedAt,
        Instant publishTime,
        Instant lockTime,
        HistoryMatchVo match,
        List<MatchResultFactHistoryVo> resultFacts,
        List<MarketSettlementHistoryVo> settlementMarkets,
        boolean recalculatedAfterFactCorrection,
        PredictionTypeEnum predictionType,
        AsianHandicapPickEnum asianHandicapPick,
        TotalGoalsPickEnum totalGoalsPick,
        PredictionAsianModelVo asianModel
) {
    public HistoryListItemVo {
        resultFacts = List.copyOf(resultFacts);
        settlementMarkets = List.copyOf(settlementMarkets);
        // prediction_type 在 V21 之前的历史行上可能为空，业务语义按体彩处理。
        predictionType = predictionType == null ? PredictionTypeEnum.SPORTTERY : predictionType;
    }

    /**
     * 亚盘字段未就绪时的兼容构造器。
     *
     * <p>统计与后台状态页的既有夹具都按 20 参构造；保留该重载可以在补齐亚盘字段的过程中
     * 不被迫一次性改写全部测试数据。
     */
    public HistoryListItemVo(
            Long predictionId,
            Integer predictionVersion,
            String modelVersion,
            String featureVersion,
            PredictionStatusEnum predictionStatus,
            BigDecimal homeWinProb,
            BigDecimal drawProb,
            BigDecimal awayWinProb,
            HandicapPickEnum handicapPick,
            BigDecimal expectedTotalGoals,
            ConfidenceLevelEnum confidenceLevel,
            String analysisSummary,
            String predictionHash,
            Instant generatedAt,
            Instant publishTime,
            Instant lockTime,
            HistoryMatchVo match,
            List<MatchResultFactHistoryVo> resultFacts,
            List<MarketSettlementHistoryVo> settlementMarkets,
            boolean recalculatedAfterFactCorrection
    ) {
        this(
                predictionId,
                predictionVersion,
                modelVersion,
                featureVersion,
                predictionStatus,
                homeWinProb,
                drawProb,
                awayWinProb,
                handicapPick,
                expectedTotalGoals,
                confidenceLevel,
                analysisSummary,
                predictionHash,
                generatedAt,
                publishTime,
                lockTime,
                match,
                resultFacts,
                settlementMarkets,
                recalculatedAfterFactCorrection,
                PredictionTypeEnum.SPORTTERY,
                null,
                null,
                null
        );
    }
}
