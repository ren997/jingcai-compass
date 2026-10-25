package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 决策引擎的出口覆盖。
 *
 * <p>重点是两个**共用同一个跳过原因**的出口（{@code !feasible()} 与残差/邻线超限）：它们对
 * 调用方看起来一样，只有指标 outcome（{@code infeasible} / {@code inconsistent}）能区分。搬移时
 * 若把这两个分支合并，管理端就无法回答「是方程无解还是市场打架」，所以这里按指标断言。</p>
 *
 * <p>另外钉住「{@code meterRegistry == null} 不抛 NPE」——回测走的就是这条路径。</p>
 */
class AsianDecisionEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant KICKOFF = NOW.plusSeconds(86_400);
    private static final Instant CAPTURE = NOW.minusSeconds(3_600);
    private static final String PROVIDER = "ASIAN_TEST";

    @Test
    void rejectsMatchThatIsNotScheduled() {
        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.FINISHED, KICKOFF, List.of(), List.of(), List.of()));

        assertThat(decision.skipReason()).isEqualTo(BaselinePredictionSkipReasonEnum.NOT_SCHEDULED);
        assertThat(decision.decided()).isFalse();
    }

    @Test
    void rejectsMatchWithoutKickoffTime() {
        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, null, List.of(), List.of(), List.of()));

        assertThat(decision.skipReason()).isEqualTo(BaselinePredictionSkipReasonEnum.KICKOFF_PASSED);
    }

    @Test
    void rejectsMatchThatAlreadyKickedOff() {
        // 边界是「严格晚于决策时刻」：相等也要跳过。
        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, NOW, List.of(), List.of(), List.of()));

        assertThat(decision.skipReason()).isEqualTo(BaselinePredictionSkipReasonEnum.KICKOFF_PASSED);
    }

    @Test
    void rejectsMatchWithoutAnyHandicapRow() {
        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, List.of(), List.of(), List.of()));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET);
    }

    @Test
    void rejectsRowsWithoutUsablePrice() {
        // 水位 1.00 不是可用价（去水倒数会得 1.0，抽水为负），整场判无效而不是当成「无数据」。
        List<AsianOddsSnapshot> rows = List.of(row(1L, "BOOK_A", "-0.50", "1.00", "1.90", "2.50", "1.90", "1.90"));

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason()).isEqualTo(BaselinePredictionSkipReasonEnum.INVALID_ASIAN_MARKET);
    }

    @Test
    void rejectsLineThatOnlyOneBookmakerQuotes() {
        // 默认 minBookmakers = 2，单家报价不具备共识资格。
        List<AsianOddsSnapshot> rows = List.of(row(1L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.90", "1.90"));

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS);
    }

    @Test
    void rejectsMatchWithoutTotalsConstraint() {
        // 一个方程两个未知数：缺大小球主盘就没有第二个约束，必须先报这一支。
        List<AsianOddsSnapshot> rows = balancedHandicap();

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, List.of(), rows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_CONSTRAINTS);
    }

    @Test
    void reportsInfeasibleLambdaSeparatelyFromInconsistentMarkets() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 让球 -3.5 与大小球 0.5 同时各占一半概率，数学上不可能同时成立。
        List<AsianOddsSnapshot> rows = List.of(
                row(1L, "BOOK_A", "-3.50", "1.90", "1.90", "0.50", "1.90", "1.90"),
                row(2L, "BOOK_B", "-3.50", "1.90", "1.90", "0.50", "1.90", "1.90"));

        AsianDecision decision = engine(registry).decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS);
        assertThat(modelOutcome(registry, "infeasible")).isEqualTo(1.0d);
        assertThat(modelOutcome(registry, "inconsistent")).isEqualTo(0.0d);
    }

    @Test
    void reportsNeighborDeviationAsInconsistentNotInfeasible() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 主盘自洽（-0.5 / 2.5 各 50%），但 3.5 那一档报出 88% 大球，回投偏差 0.63 >> 0.04。
        List<AsianOddsSnapshot> rows = List.of(
                row(1L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.90", "1.90"),
                row(2L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.90", "1.90"),
                row(3L, "BOOK_C", "-0.50", "1.90", "1.90", "3.50", "1.11", "8.00"),
                row(4L, "BOOK_D", "-0.50", "1.90", "1.90", "3.50", "1.11", "8.00"));

        AsianDecision decision = engine(registry).decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS);
        assertThat(modelOutcome(registry, "inconsistent")).isEqualTo(1.0d);
        assertThat(modelOutcome(registry, "infeasible")).isEqualTo(0.0d);
    }

    @Test
    void rejectsWhenNoRealRowQuotesTheConsensusLine() {
        List<AsianOddsSnapshot> rows = balancedHandicap();

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), List.of()));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE);
    }

    @Test
    void rejectsWhenFrozenTotalsLineDiffersFromConsensus() {
        // 共识大小球主盘是 2.5，但所有可冻结行都报 3.5：按被抬高的线结算会比真实盘口更容易赢。
        List<AsianOddsSnapshot> rows = balancedHandicap();
        List<AsianOddsSnapshot> freezeRows = List.of(
                row(11L, "BOOK_A", "-0.50", "1.90", "1.90", "3.50", "1.90", "1.90"),
                row(12L, "BOOK_B", "-0.50", "1.90", "1.90", "3.50", "1.90", "1.90"));

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), freezeRows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE);
    }

    @Test
    void attributesBothMarketsRejectionToDispersionWhenSigmaExceedsOnBoth() {
        // 两家给出完全相反的定价（保证金一致所以不算错价），σ = 0.0526 > 0.02，两个市场都不可信。
        List<AsianOddsSnapshot> rows = List.of(
                row(1L, "BOOK_A", "-0.50", "1.70", "2.10", "2.50", "1.70", "2.10"),
                row(2L, "BOOK_B", "-0.50", "2.10", "1.70", "2.50", "2.10", "1.70"));

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.MARKET_DISPERSION_TOO_HIGH);
        assertThat(decision.handicap().dispersionExceeded()).isTrue();
        assertThat(decision.totals().dispersionExceeded()).isTrue();
    }

    @Test
    void attributesBothMarketsRejectionToLowConfidenceWhenBalanced() {
        // 两侧都 1.90/1.90：σ = 0 不算分歧，但 signal = 0，两个市场都没方向。
        List<AsianOddsSnapshot> rows = balancedHandicap();

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS);
        assertThat(decision.handicap().dispersionExceeded()).isFalse();
        assertThat(decision.totals().dispersionExceeded()).isFalse();
        // 诊断量仍然保留：回测要靠它区分「σ 太大」与「太平衡」。
        assertThat(decision.frozen()).isNotNull();
        assertThat(decision.solution()).isNotNull();
        assertThat(decision.handicap().coverProbability()).isEqualTo(0.5d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void decidesTotalsDirectionFromFrozenRowWhenItCarriesSignal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 让球两侧对称（无方向），大小球 1.80/2.00 → 大球 MEDIUM 且抽水仅 5.5%。
        List<AsianOddsSnapshot> rows = List.of(
                row(501L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.80", "2.00"),
                row(502L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95"));

        AsianDecision decision = engine(registry).decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason()).isNull();
        assertThat(decision.decided()).isTrue();
        assertThat(decision.handicap().pick()).isNull();
        assertThat(decision.handicap().confidence()).isEqualTo(ConfidenceLevelEnum.LOW);
        assertThat(decision.totals().pick()).isEqualTo(TotalGoalsPickEnum.OVER);
        assertThat(decision.totals().confidence()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
        // 冻结行必须真实存在于候选里，且大小球线等于共识主盘线。
        assertThat(decision.frozen().getId()).isIn(501L, 502L);
        assertThat(decision.frozen().getTotalLine()).isEqualByComparingTo("2.50");
        assertThat(decision.featureAsOf()).isEqualTo(CAPTURE);
        // 让球方向被低置信挡掉后不应产生 gate 记录，大小球方向才有。
        assertThat(decision.handicap().gate()).isNull();
        assertThat(decision.totals().gate()).isNotNull();
        assertThat(gateCount(registry, "totals")).isEqualTo(1.0d);
        assertThat(gateCount(registry, "handicap")).isEqualTo(0.0d);
    }

    @Test
    void worksWithoutMeterRegistry() {
        // 回测路径：null registry 下所有指标调用必须静默跳过，而不是 NPE。
        List<AsianOddsSnapshot> rows = balancedHandicap();

        AsianDecision decision = new AsianDecisionEngine(
                AsianOddsAggregationProperties.defaults(), AsianModelProperties.defaults(), null)
                .decide(input(MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason())
                .isEqualTo(BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS);
    }

    @Test
    void bothPickAndConfidenceSurviveOnlyWhenDirectionExists() {
        // 让球 HOME_COVER 时方向与置信度必须成对出现，落库才能过「无方向 ⇒ 无置信度」校验。
        List<AsianOddsSnapshot> rows = List.of(
                row(1L, "BOOK_A", "-0.50", "1.50", "2.60", "4.50", "1.50", "2.60"),
                row(2L, "BOOK_B", "-0.50", "1.52", "2.55", "4.50", "1.52", "2.55"));

        AsianDecision decision = engine().decide(input(
                MatchStatusEnum.SCHEDULED, KICKOFF, rows, totals(rows), rows));

        assertThat(decision.skipReason()).isNull();
        assertThat(decision.handicap().pick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
        assertThat(decision.handicap().confidence()).isEqualTo(ConfidenceLevelEnum.HIGH);
    }

    // ---------------------------------------------------------------- fixtures

    /** 让球与大小球都对称：两侧 1.90，共识概率必然 50/50、σ 为 0。 */
    private static List<AsianOddsSnapshot> balancedHandicap() {
        return List.of(
                row(1L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.90", "1.90"),
                row(2L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.90", "1.90"));
    }

    private static List<AsianMarketConsensusAggregator.Quote> totals(List<AsianOddsSnapshot> rows) {
        return rows.stream()
                .filter(row -> row.getTotalLine() != null)
                .map(row -> new AsianMarketConsensusAggregator.Quote(
                        row.getProviderCode(), row.getBookmakerCode(), row.getTotalLine(),
                        row.getOverOdds(), row.getUnderOdds(), row.getCapturedAt(), row.getId()))
                .toList();
    }

    private static AsianOddsSnapshot row(
            Long id,
            String bookmaker,
            String handicapLine,
            String homeOdds,
            String awayOdds,
            String totalLine,
            String overOdds,
            String underOdds
    ) {
        AsianOddsSnapshot snapshot = new AsianOddsSnapshot();
        snapshot.setId(id);
        snapshot.setMatchId(101L);
        snapshot.setProviderCode(PROVIDER);
        snapshot.setBookmakerCode(bookmaker);
        snapshot.setHandicapLine(new BigDecimal(handicapLine));
        snapshot.setHomeOdds(new BigDecimal(homeOdds));
        snapshot.setAwayOdds(new BigDecimal(awayOdds));
        snapshot.setTotalLine(new BigDecimal(totalLine));
        snapshot.setOverOdds(new BigDecimal(overOdds));
        snapshot.setUnderOdds(new BigDecimal(underOdds));
        snapshot.setCapturedAt(CAPTURE);
        return snapshot;
    }

    private static AsianDecisionInput input(
            MatchStatusEnum status,
            Instant kickoff,
            List<AsianOddsSnapshot> handicapRows,
            List<AsianMarketConsensusAggregator.Quote> totalsQuotes,
            List<AsianOddsSnapshot> freezeRows
    ) {
        return new AsianDecisionInput(101L, status, kickoff, NOW, handicapRows, totalsQuotes, freezeRows);
    }

    private static AsianDecisionEngine engine() {
        return engine(null);
    }

    private static AsianDecisionEngine engine(MeterRegistry registry) {
        return new AsianDecisionEngine(
                AsianOddsAggregationProperties.defaults(), AsianModelProperties.defaults(), registry);
    }

    private static double modelOutcome(MeterRegistry registry, String outcome) {
        var counter = registry.find("jingcai.prediction.asian.model").tag("outcome", outcome).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static double gateCount(MeterRegistry registry, String market) {
        var counter = registry.find("jingcai.prediction.asian.gate").tag("market", market).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
