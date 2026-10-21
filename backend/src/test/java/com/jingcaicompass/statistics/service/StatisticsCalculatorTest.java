package com.jingcaicompass.statistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.HistoryMatchVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.history.vo.SettlementVersionVo;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.vo.PredictionAsianModelVo;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.statistics.enums.ProbabilityMetricUnavailableReasonEnum;
import com.jingcaicompass.statistics.enums.ProbabilityScoringModeEnum;
import com.jingcaicompass.statistics.enums.RoiUnavailableReasonEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticsCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    private final StatisticsCalculator calculator = new StatisticsCalculator();

    @Test
    void calculatesThreeClassProbabilityAndBothMarketMetricsFromCurrentFinalFact() {
        var result = calculator.calculate(List.of(record(
                MatchResultFactStatusEnum.FINAL,
                2,
                1,
                new BigDecimal("0.600000"),
                new BigDecimal("0.200000"),
                new BigDecimal("0.200000"),
                SettlementStatusEnum.HIT,
                SettlementStatusEnum.MISS
        )), PredictionTypeEnum.SPORTTERY);

        assertThat(result.lockedPredictionCount()).isOne();
        assertThat(result.finalFactCount()).isOne();
        assertThat(result.probabilityMetrics().scoringMode()).isEqualTo(ProbabilityScoringModeEnum.THREE_WAY);
        assertThat(result.probabilityMetrics().sampleSize()).isOne();
        assertThat(result.probabilityMetrics().brierScore()).isEqualByComparingTo("0.240000");
        assertThat(result.probabilityMetrics().logLoss()).isEqualByComparingTo("0.510826");
        // 体彩没有市场侧概率，双 Brier 的二三项必须留空而不是填 0。
        assertThat(result.probabilityMetrics().marketBrierScore()).isNull();
        assertThat(result.probabilityMetrics().modelOutperformsMarket()).isNull();
        assertThat(result.markets()).hasSize(2);
        assertThat(result.markets().get(0).marketType()).isEqualTo(MarketTypeEnum.HAD);
        assertThat(result.markets().get(0).hitRate()).isEqualByComparingTo("1.000000");
        assertThat(result.markets().get(1).marketType()).isEqualTo(MarketTypeEnum.HHAD);
        assertThat(result.markets().get(1).hitRate()).isEqualByComparingTo("0.000000");
        assertThat(result.roi().available()).isFalse();
        assertThat(result.roi().unavailableReasons()).containsExactly(
                RoiUnavailableReasonEnum.MISSING_FIXED_BETTING_RULE,
                RoiUnavailableReasonEnum.MISSING_LOCKED_BETTING_MARKET,
                RoiUnavailableReasonEnum.MISSING_LOCKED_ODDS_INPUT
        );
    }

    @Test
    void excludesPendingAndVoidFactsFromProbabilitySampleAndKeepsMarketStatusesVisible() {
        var result = calculator.calculate(List.of(
                record(
                        MatchResultFactStatusEnum.PENDING,
                        null,
                        null,
                        new BigDecimal("0.500000"),
                        new BigDecimal("0.250000"),
                        new BigDecimal("0.250000"),
                        SettlementStatusEnum.PENDING,
                        SettlementStatusEnum.PENDING
                ),
                record(
                        MatchResultFactStatusEnum.VOID,
                        null,
                        null,
                        new BigDecimal("0.500000"),
                        new BigDecimal("0.250000"),
                        new BigDecimal("0.250000"),
                        SettlementStatusEnum.VOID,
                        SettlementStatusEnum.VOID
                )
        ), PredictionTypeEnum.SPORTTERY);

        assertThat(result.finalFactCount()).isZero();
        assertThat(result.pendingFactCount()).isOne();
        assertThat(result.voidFactCount()).isOne();
        assertThat(result.probabilityMetrics().brierScore()).isNull();
        assertThat(result.probabilityMetrics().unavailableReasons()).containsExactly(
                ProbabilityMetricUnavailableReasonEnum.NO_FINAL_SAMPLE
        );
        assertThat(result.markets().get(0).pendingCount()).isOne();
        assertThat(result.markets().get(0).voidCount()).isOne();
        assertThat(result.markets().get(0).settledSampleSize()).isZero();
    }

    @Test
    void appliesDocumentedFloorWhenActualOutcomeProbabilityIsZero() {
        var result = calculator.calculate(List.of(record(
                MatchResultFactStatusEnum.FINAL,
                0,
                1,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SettlementStatusEnum.MISS,
                SettlementStatusEnum.MISS
        )), PredictionTypeEnum.SPORTTERY);

        assertThat(result.probabilityMetrics().logLoss()).isEqualByComparingTo("34.538776");
    }

    @Test
    void ignoresRecordsFromTheOtherProductLineInsteadOfMixingDenominators() {
        // 亚盘记录混进体彩请求时不能算进任何分母，也不能因为缺 HAD 而抛错。
        var result = calculator.calculate(
                List.of(asianRecord(
                        AsianHandicapPickEnum.HOME_COVER,
                        TotalGoalsPickEnum.UNDER,
                        new BigDecimal("0.600000"),
                        new BigDecimal("0.550000"),
                        new BigDecimal("0.400000"),
                        new BigDecimal("0.450000"),
                        SettlementStatusEnum.HIT,
                        SettlementStatusEnum.PUSH,
                        new BigDecimal("0.9200")
                )),
                PredictionTypeEnum.SPORTTERY
        );

        assertThat(result.lockedPredictionCount()).isZero();
        assertThat(result.finalFactCount()).isZero();
        assertThat(result.markets()).hasSize(2);
        assertThat(result.markets().get(0).settledSampleSize()).isZero();
    }

    @Test
    void scoresAsianDirectionWithPairedModelAndMarketBrierAndContinuousOutcomes() {
        var result = calculator.calculate(
                List.of(asianRecord(
                        AsianHandicapPickEnum.HOME_COVER,
                        TotalGoalsPickEnum.UNDER,
                        new BigDecimal("0.600000"),
                        new BigDecimal("0.550000"),
                        new BigDecimal("0.400000"),
                        new BigDecimal("0.450000"),
                        SettlementStatusEnum.HIT,
                        SettlementStatusEnum.PUSH,
                        new BigDecimal("0.9200")
                )),
                PredictionTypeEnum.ASIAN
        );

        // 两个方向各一个样本：让球 HIT(outcome 1) + 大小球 UNDER/PUSH(outcome 0.5)。
        // 大小球押小球，方向概率取 1 − 大球概率：模型 0.60、市场 0.55。
        assertThat(result.probabilityMetrics().scoringMode()).isEqualTo(ProbabilityScoringModeEnum.ASIAN_DIRECTION);
        assertThat(result.probabilityMetrics().sampleSize()).isEqualTo(2);
        assertThat(result.probabilityMetrics().brierScore()).isEqualByComparingTo("0.085000");
        assertThat(result.probabilityMetrics().marketBrierScore()).isEqualByComparingTo("0.102500");
        assertThat(result.probabilityMetrics().brierAdvantage()).isEqualByComparingTo("0.017500");
        assertThat(result.probabilityMetrics().modelOutperformsMarket()).isTrue();
        // 亚盘没有胜平负三分类，LogLoss 必须留空而不是拿二分类值冒充。
        assertThat(result.probabilityMetrics().logLoss()).isNull();

        // 收益：HIT 盈利 0.92 + PUSH 走盘 0 = 0.92，两注本金 2 → roi 0.46；
        // yield 分母剔除走盘（1 注承担风险）→ 0.92。
        assertThat(result.roi().available()).isTrue();
        assertThat(result.roi().sampleSize()).isEqualTo(2);
        assertThat(result.roi().roi()).isEqualByComparingTo("0.460000");
        assertThat(result.roi().yield()).isEqualByComparingTo("0.920000");
    }

    @Test
    void convertsFirstSideProbabilityToThePickedSideInsteadOfScoringTheWrongDirection() {
        // 押客队赢盘，模型给主队赢盘的 0.60，方向概率必须是 0.40。
        // 若误用首边值，Brier 会算成 (0.60-1)² = 0.16 而不是 (0.40-1)² = 0.36。
        var result = calculator.calculate(
                List.of(asianRecord(
                        AsianHandicapPickEnum.AWAY_COVER,
                        null,
                        new BigDecimal("0.600000"),
                        new BigDecimal("0.600000"),
                        null,
                        null,
                        SettlementStatusEnum.HIT,
                        null,
                        new BigDecimal("0.9200")
                )),
                PredictionTypeEnum.ASIAN
        );

        assertThat(result.probabilityMetrics().sampleSize()).isOne();
        assertThat(result.probabilityMetrics().brierScore()).isEqualByComparingTo("0.360000");
        assertThat(result.probabilityMetrics().marketBrierScore()).isEqualByComparingTo("0.360000");
    }

    @Test
    void countsEverySettlementStatusWithoutSilentlyDroppingNewOnes() {
        List<HistoryListItemVo> records = new ArrayList<>();
        records.add(asianRecord(AsianHandicapPickEnum.HOME_COVER, null,
                new BigDecimal("0.600000"), new BigDecimal("0.500000"), null, null,
                SettlementStatusEnum.HIT, null, new BigDecimal("0.9200")));
        records.add(asianRecord(AsianHandicapPickEnum.HOME_COVER, null,
                new BigDecimal("0.600000"), new BigDecimal("0.500000"), null, null,
                SettlementStatusEnum.MISS, null, new BigDecimal("-1.0000")));
        records.add(asianRecord(AsianHandicapPickEnum.HOME_COVER, null,
                new BigDecimal("0.600000"), new BigDecimal("0.500000"), null, null,
                SettlementStatusEnum.PUSH, null, BigDecimal.ZERO));
        records.add(asianRecord(AsianHandicapPickEnum.HOME_COVER, null,
                new BigDecimal("0.600000"), new BigDecimal("0.500000"), null, null,
                SettlementStatusEnum.HALF_WIN, null, new BigDecimal("0.4100")));
        records.add(asianRecord(AsianHandicapPickEnum.HOME_COVER, null,
                new BigDecimal("0.600000"), new BigDecimal("0.500000"), null, null,
                SettlementStatusEnum.HALF_LOSS, null, new BigDecimal("-0.5000")));

        var result = calculator.calculate(records, PredictionTypeEnum.ASIAN);
        var handicap = result.markets().get(0);

        assertThat(handicap.hitCount()).isOne();
        assertThat(handicap.missCount()).isOne();
        assertThat(handicap.pushCount()).isOne();
        assertThat(handicap.halfWinCount()).isOne();
        assertThat(handicap.halfLossCount()).isOne();
        // 走盘不进命中率分母；半赢计入「不含走盘」口径。
        assertThat(handicap.settledSampleSize()).isEqualTo(4);
        assertThat(handicap.hitRate()).isEqualByComparingTo("0.500000");
        assertThat(handicap.weightedHitRate()).isEqualByComparingTo("0.375000");
        // 5 注全部已结算，走盘 roi 被稀释，yield 剔除走盘。
        assertThat(result.roi().sampleSize()).isEqualTo(5);
        assertThat(result.roi().roi()).isEqualByComparingTo("-0.034000");
    }

    @Test
    void neverThrowsWhenAnAsianRecordCarriesNoMarketHistory() {
        // 亚盘允许只给出单侧方向，缺市场是正常形态，不能像体彩那样抛错。
        assertThatCode(() -> calculator.calculate(
                List.of(new HistoryListItemVo(
                        2L, 1, "t306-asian-baseline-v6", "t306-asian-mainline-v6",
                        PredictionStatusEnum.LOCKED,
                        null, null, null, null, null, null,
                        "亚盘测试预测", "b".repeat(64),
                        NOW, NOW, NOW,
                        new HistoryMatchVo(10L, LocalDate.of(2026, 7, 27), "T613-001", 8L, "T613 联赛", "主队", "客队", NOW),
                        List.of(new MatchResultFactHistoryVo(
                                20L, 1, null, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED,
                                1, 0, NOW, true, NOW)),
                        List.of(),
                        false,
                        PredictionTypeEnum.ASIAN,
                        AsianHandicapPickEnum.HOME_COVER,
                        null,
                        new PredictionAsianModelVo(
                                null, null,
                                new BigDecimal("0.600000"), new BigDecimal("0.550000"), new BigDecimal("0.050000"),
                                null, null, null, null, null)
                )),
                PredictionTypeEnum.ASIAN
        )).doesNotThrowAnyException();
    }

    @Test
    void reportsMissingAsianModelProbabilityInsteadOfScoringZero() {
        // 有方向、有结算，但十列概率为空（V24 之前的草稿）：必须显式报缺概率，不能把 null 当成 0 计分。
        var result = calculator.calculate(
                List.of(new HistoryListItemVo(
                        4L, 1, "t306-asian-baseline-v5", "t306-asian-mainline-v5",
                        PredictionStatusEnum.LOCKED,
                        null, null, null, null, null, null,
                        "亚盘缺概率预测", "d".repeat(64),
                        NOW, NOW, NOW,
                        new HistoryMatchVo(10L, LocalDate.of(2026, 7, 27), "T613-001", 8L, "T613 联赛", "主队", "客队", NOW),
                        List.of(new MatchResultFactHistoryVo(
                                20L, 1, null, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED,
                                2, 1, NOW, true, NOW)),
                        List.of(asianMarket(MarketTypeEnum.ASIAN_HANDICAP, SettlementStatusEnum.HIT, new BigDecimal("0.9200"))),
                        false,
                        PredictionTypeEnum.ASIAN,
                        AsianHandicapPickEnum.HOME_COVER,
                        null,
                        new PredictionAsianModelVo(
                                new BigDecimal("1.500000"), new BigDecimal("1.200000"),
                                null, null, null, null, null, null,
                                new BigDecimal("0.012000"), null)
                )),
                PredictionTypeEnum.ASIAN
        );

        assertThat(result.probabilityMetrics().sampleSize()).isZero();
        assertThat(result.probabilityMetrics().brierScore()).isNull();
        // 两个原因都应报：既没有可评分的样本，也缺模型概率——只说前者会把排障引向「没结算」。
        assertThat(result.probabilityMetrics().unavailableReasons()).containsExactly(
                ProbabilityMetricUnavailableReasonEnum.NO_SETTLED_ASIAN_SAMPLE,
                ProbabilityMetricUnavailableReasonEnum.MISSING_ASIAN_MODEL_PROBABILITY
        );
    }

    private HistoryListItemVo record(
            MatchResultFactStatusEnum factStatus,
            Integer homeScore,
            Integer awayScore,
            BigDecimal home,
            BigDecimal draw,
            BigDecimal away,
            SettlementStatusEnum hadStatus,
            SettlementStatusEnum hhadStatus
    ) {
        return new HistoryListItemVo(
                1L,
                1,
                "t507-model",
                "t507-feature",
                PredictionStatusEnum.LOCKED,
                home,
                draw,
                away,
                HandicapPickEnum.HOME_WIN,
                new BigDecimal("2.50"),
                ConfidenceLevelEnum.MEDIUM,
                "统计测试预测",
                "a".repeat(64),
                NOW,
                NOW,
                NOW,
                new HistoryMatchVo(10L, LocalDate.of(2026, 7, 27), "T507-001", 8L, "T507 联赛", "主队", "客队", NOW),
                List.of(new MatchResultFactHistoryVo(
                        20L,
                        1,
                        null,
                        factStatus,
                        factStatus == MatchResultFactStatusEnum.FINAL ? MatchStatusEnum.FINISHED : MatchStatusEnum.POSTPONED,
                        homeScore,
                        awayScore,
                        NOW,
                        true,
                        NOW
                )),
                List.of(
                        new MarketSettlementHistoryVo(MarketTypeEnum.HAD, hadStatus, hadStatus != SettlementStatusEnum.PENDING, false, List.of()),
                        new MarketSettlementHistoryVo(MarketTypeEnum.HHAD, hhadStatus, hhadStatus != SettlementStatusEnum.PENDING, false, List.of())
                ),
                false
        );
    }

    private HistoryListItemVo asianRecord(
            AsianHandicapPickEnum handicapPick,
            TotalGoalsPickEnum totalsPick,
            BigDecimal modelCover,
            BigDecimal marketCover,
            BigDecimal modelOver,
            BigDecimal marketOver,
            SettlementStatusEnum handicapStatus,
            SettlementStatusEnum totalsStatus,
            BigDecimal handicapProfit
    ) {
        List<MarketSettlementHistoryVo> markets = new ArrayList<>(2);
        markets.add(asianMarket(MarketTypeEnum.ASIAN_HANDICAP, handicapStatus, handicapProfit));
        markets.add(asianMarket(
                MarketTypeEnum.ASIAN_TOTAL_GOALS,
                totalsStatus == null ? SettlementStatusEnum.PENDING : totalsStatus,
                totalsStatus == null ? null : BigDecimal.ZERO
        ));
        PredictionAsianModelVo model = modelCover == null && modelOver == null
                ? null
                : new PredictionAsianModelVo(
                        new BigDecimal("1.500000"),
                        new BigDecimal("1.200000"),
                        modelCover,
                        marketCover,
                        modelCover == null ? null : modelCover.subtract(marketCover),
                        modelOver,
                        marketOver,
                        modelOver == null ? null : modelOver.subtract(marketOver),
                        new BigDecimal("0.012000"),
                        new BigDecimal("0.009000")
                );
        return new HistoryListItemVo(
                3L,
                1,
                "t306-asian-baseline-v6",
                "t306-asian-mainline-v6",
                PredictionStatusEnum.LOCKED,
                null,
                null,
                null,
                null,
                null,
                null,
                "亚盘测试预测",
                "c".repeat(64),
                NOW,
                NOW,
                NOW,
                new HistoryMatchVo(10L, LocalDate.of(2026, 7, 27), "T613-001", 8L, "T613 联赛", "主队", "客队", NOW),
                List.of(new MatchResultFactHistoryVo(
                        20L, 1, null, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED,
                        2, 1, NOW, true, NOW)),
                markets,
                false,
                PredictionTypeEnum.ASIAN,
                handicapPick,
                totalsPick,
                model
        );
    }

    private MarketSettlementHistoryVo asianMarket(
            MarketTypeEnum marketType,
            SettlementStatusEnum status,
            BigDecimal profit
    ) {
        List<SettlementVersionVo> versions = status == SettlementStatusEnum.PENDING
                ? List.of()
                : List.of(new SettlementVersionVo(
                        30L, 1, null, status, 20L, "t613-v1", true, NOW, profit));
        return new MarketSettlementHistoryVo(marketType, status, status != SettlementStatusEnum.PENDING, false, versions);
    }
}
