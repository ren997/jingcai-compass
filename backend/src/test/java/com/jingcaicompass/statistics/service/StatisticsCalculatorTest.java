package com.jingcaicompass.statistics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.HistoryMatchVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.statistics.enums.ProbabilityMetricUnavailableReasonEnum;
import com.jingcaicompass.statistics.enums.RoiUnavailableReasonEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticsCalculatorTest {

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
        )));

        assertThat(result.lockedPredictionCount()).isOne();
        assertThat(result.finalFactCount()).isOne();
        assertThat(result.probabilityMetrics().sampleSize()).isOne();
        assertThat(result.probabilityMetrics().brierScore()).isEqualByComparingTo("0.240000");
        assertThat(result.probabilityMetrics().logLoss()).isEqualByComparingTo("0.510826");
        assertThat(result.had().hitRate()).isEqualByComparingTo("1.000000");
        assertThat(result.hhad().hitRate()).isEqualByComparingTo("0.000000");
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
        ));

        assertThat(result.finalFactCount()).isZero();
        assertThat(result.pendingFactCount()).isOne();
        assertThat(result.voidFactCount()).isOne();
        assertThat(result.probabilityMetrics().brierScore()).isNull();
        assertThat(result.probabilityMetrics().unavailableReasons()).containsExactly(
                ProbabilityMetricUnavailableReasonEnum.NO_FINAL_SAMPLE
        );
        assertThat(result.had().pendingCount()).isOne();
        assertThat(result.had().voidCount()).isOne();
        assertThat(result.had().settledSampleSize()).isZero();
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
        )));

        assertThat(result.probabilityMetrics().logLoss()).isEqualByComparingTo("34.538776");
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
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
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
                now,
                now,
                now,
                new HistoryMatchVo(10L, LocalDate.of(2026, 7, 27), "T507-001", 8L, "T507 联赛", "主队", "客队", now),
                List.of(new MatchResultFactHistoryVo(
                        20L,
                        1,
                        null,
                        factStatus,
                        factStatus == MatchResultFactStatusEnum.FINAL ? MatchStatusEnum.FINISHED : MatchStatusEnum.POSTPONED,
                        homeScore,
                        awayScore,
                        now,
                        true,
                        now
                )),
                List.of(
                        new MarketSettlementHistoryVo(MarketTypeEnum.HAD, hadStatus, hadStatus != SettlementStatusEnum.PENDING, false, List.of()),
                        new MarketSettlementHistoryVo(MarketTypeEnum.HHAD, hhadStatus, hhadStatus != SettlementStatusEnum.PENDING, false, List.of())
                ),
                false
        );
    }
}
