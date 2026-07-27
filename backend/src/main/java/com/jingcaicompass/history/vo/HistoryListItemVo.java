package com.jingcaicompass.history.vo;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
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
        boolean recalculatedAfterFactCorrection
) {
    public HistoryListItemVo {
        resultFacts = List.copyOf(resultFacts);
        settlementMarkets = List.copyOf(settlementMarkets);
    }
}
