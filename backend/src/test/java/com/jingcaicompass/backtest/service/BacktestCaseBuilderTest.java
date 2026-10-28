package com.jingcaicompass.backtest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.backtest.dto.BacktestCandidateDto;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.enums.BacktestDecisionStageEnum;
import com.jingcaicompass.backtest.enums.BacktestResultStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator.LineConsensus;
import com.jingcaicompass.odds.service.AsianPublishGate;
import com.jingcaicompass.odds.service.ImpliedPoissonModel;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.service.AsianDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 样本落库映射的逐列断言。
 *
 * <p>这些列写错<b>不会报错</b>：诊断列错位只会让「为什么没发」的结论偏移，
 * 概率方向反了只会让 Brier 看起来更好。所以这里对每一列单独钉一条。
 */
class BacktestCaseBuilderTest {

    private static final Instant KICKOFF = Instant.parse("2026-06-20T12:00:00Z");

    private static final Instant DECISION_AT = Instant.parse("2026-06-20T09:00:00Z");

    private static final BacktestCandidateDto CANDIDATE = new BacktestCandidateDto(
            11L, "2042155", LocalDate.of(2026, 6, 20), "周六001", "巴西甲",
            "主队", "客队", KICKOFF, MatchStatusEnum.SCHEDULED);

    @Test
    void mapsDecidedDecisionWithFirstSideProbabilities() {
        AsianOddsSnapshot frozen = frozenRow();
        AsianDecision decision = decision(
                null,
                frozen,
                consensus("0.55"),
                consensus("0.60"),
                solution(ImpliedPoissonModel.Infeasibility.NONE, 0.0),
                new AsianDecision.HandicapLeg(
                        AsianHandicapPickEnum.AWAY_COVER, ConfidenceLevelEnum.MEDIUM, false,
                        verdict(true), 0.42),
                new AsianDecision.TotalsLeg(
                        TotalGoalsPickEnum.OVER, ConfidenceLevelEnum.HIGH, false,
                        verdict(true), 0.58)
        );

        BacktestCaseEntity entity = BacktestCaseBuilder.build(7L, "baseline", CANDIDATE, DECISION_AT, 91L, decision);

        assertThat(entity.getRunId()).isEqualTo(7L);
        assertThat(entity.getGroupKey()).isEqualTo("baseline");
        assertThat(entity.getArchiveId()).isEqualTo(91L);
        assertThat(entity.getDecisionAt()).isEqualTo(DECISION_AT);
        assertThat(entity.getKickoffTime()).isEqualTo(KICKOFF);
        assertThat(entity.getDecisionStage()).isEqualTo(BacktestDecisionStageEnum.DECIDED);
        assertThat(entity.getSkipReason()).isNull();
        assertThat(entity.getResultStatus()).isEqualTo(BacktestResultStatusEnum.PENDING);

        // 冻结行七列原样带出：结算只认这一行。
        assertThat(entity.getFrozenBookmakerCode()).isEqualTo("pinnacle");
        assertThat(entity.getFrozenHandicapLine()).isEqualByComparingTo("-0.50");
        assertThat(entity.getFrozenHomeOdds()).isEqualByComparingTo("1.9000");
        assertThat(entity.getFrozenAwayOdds()).isEqualByComparingTo("2.0000");
        assertThat(entity.getFrozenTotalLine()).isEqualByComparingTo("2.75");

        // 概率一律首边：模型 0.42 是「主队赢盘」概率，即便方向押的是客队。
        assertThat(entity.getModelCoverProb()).isEqualByComparingTo("0.420000");
        assertThat(entity.getMarketCoverProb()).isEqualByComparingTo("0.550000");
        assertThat(entity.getEdgeCover()).isEqualByComparingTo("-0.130000");
        assertThat(entity.getModelOverProb()).isEqualByComparingTo("0.580000");
        assertThat(entity.getMarketOverProb()).isEqualByComparingTo("0.600000");

        assertThat(entity.getHandicapPick()).isEqualTo(AsianHandicapPickEnum.AWAY_COVER);
        assertThat(entity.getHandicapConfidence()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
        assertThat(entity.getTotalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
        assertThat(entity.getTotalsConfidence()).isEqualTo(ConfidenceLevelEnum.HIGH);
    }

    @Test
    void keepsConfidenceNullWhenPickIsNull() {
        // 引擎在「两个市场都没方向」时带 skipReason（LOW_CONFIDENCE_ASIAN_MARKETS），
        // 但仍持有冻结行与两个 leg——这个形态才是落库要处理的那种。
        AsianDecision decision = decision(
                BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS,
                frozenRow(),
                consensus("0.52"),
                consensus("0.51"),
                solution(ImpliedPoissonModel.Infeasibility.NONE, 0.0),
                // 引擎在 LOW 档只清 pick 不清 confidence 的情况不该发生，但落库必须先挡住；
                // ck_backtest_cases_pick 要求两列同生同灭。
                new AsianDecision.HandicapLeg(
                        null, ConfidenceLevelEnum.LOW, false, null, 0.52),
                null
        );

        BacktestCaseEntity entity = BacktestCaseBuilder.build(7L, "g", CANDIDATE, DECISION_AT, 1L, decision);

        assertThat(entity.getHandicapPick()).isNull();
        assertThat(entity.getHandicapConfidence()).isNull();
        assertThat(entity.getTotalsPick()).isNull();
        assertThat(entity.getTotalsConfidence()).isNull();
        // 概率仍然写入：低置信只表示不发布方向，不代表模型概率为 0。写 0 会让 Brier 把「没方向」当成「强烈看空」。
        assertThat(entity.getModelCoverProb()).isEqualByComparingTo("0.520000");
        assertThat(entity.getMarketCoverProb()).isEqualByComparingTo("0.520000");
        assertThat(entity.getModelOverProb()).isNull();
    }

    @Test
    void leavesFrozenColumnsNullWhenSkippedBeforeSelection() {
        AsianDecision decision = decision(
                BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS,
                null, null, null,
                solution(ImpliedPoissonModel.Infeasibility.NONE, 0.0),
                null, null
        );

        BacktestCaseEntity entity = BacktestCaseBuilder.build(7L, "g", CANDIDATE, DECISION_AT, 1L, decision);

        assertThat(entity.getDecisionStage()).isEqualTo(BacktestDecisionStageEnum.INSUFFICIENT_HANDICAP);
        assertThat(entity.getSkipReason()).isEqualTo(BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS);
        assertThat(entity.getFrozenBookmakerCode()).isNull();
        assertThat(entity.getFrozenHandicapLine()).isNull();
        assertThat(entity.getFrozenCapturedAt()).isNull();
        assertThat(entity.getModelCoverProb()).isNull();
        assertThat(entity.getMarketCoverProb()).isNull();
        assertThat(entity.getEdgeCover()).isNull();
        assertThat(entity.getHandicapGatePass()).isNull();
    }

    @Test
    void separatesInfeasibleFromInconsistent() {
        // 同一个枚举值、两个不同出口：靠 solution.feasible() 区分，不能合并。
        AsianDecision infeasible = decision(
                BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS,
                null, null, null,
                solution(ImpliedPoissonModel.Infeasibility.LAMBDA_SPLIT_IMPOSSIBLE, 0.0),
                null, null
        );
        AsianDecision inconsistent = decision(
                BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS,
                null, null, null,
                solution(ImpliedPoissonModel.Infeasibility.NONE, 0.05),
                null, null
        );

        assertThat(BacktestCaseBuilder.build(1L, "g", CANDIDATE, DECISION_AT, 1L, infeasible).getDecisionStage())
                .isEqualTo(BacktestDecisionStageEnum.INFEASIBLE);
        assertThat(BacktestCaseBuilder.build(1L, "g", CANDIDATE, DECISION_AT, 1L, inconsistent).getDecisionStage())
                .isEqualTo(BacktestDecisionStageEnum.INCONSISTENT);
    }

    @Test
    void recordsNeighborDeviationAndResidual() {
        ImpliedPoissonModel.Solution solution = solution(ImpliedPoissonModel.Infeasibility.NONE, 0.0375);
        AsianDecision decision = decision(
                null,
                frozenRow(),
                consensus("0.50"),
                consensus("0.50"),
                solution,
                new AsianDecision.HandicapLeg(
                        AsianHandicapPickEnum.HOME_COVER, ConfidenceLevelEnum.HIGH, false, verdict(false), 0.53),
                null,
                new ImpliedPoissonModel.NeighborCheck(0.0281, 3, ImpliedPoissonModel.Market.HANDICAP,
                        new BigDecimal("-1.25"))
        );

        BacktestCaseEntity entity = BacktestCaseBuilder.build(7L, "g", CANDIDATE, DECISION_AT, 1L, decision);

        assertThat(entity.getMaxResidual()).isEqualTo(0.0375);
        assertThat(entity.getNeighborMaxDeviation()).isEqualByComparingTo("0.028100");
        // gate.wouldPublish = false 时仍要写 false，不能因为「没发布」就留空：
        // 留空与 false 在对比页是两个结论（「没走到这道闸」vs「走到了但没过」）。
        assertThat(entity.getHandicapGatePass()).isFalse();
        assertThat(entity.getTotalsGatePass()).isNull();
    }

    @Test
    void recordsInfeasibilityReasonOnlyWhenSolveFails() {
        // 四种不可行原因必须能单独统计。此前 8 月 117 场、9 月 165 场全被记成同一个
        // INCONSISTENT_ASIAN_MARKETS，和「残差/邻线超限」合在一起看，分布会误导成
        // 「市场在打架」——而四种原因里三种是搜索域或数据问题，处置方向完全不同。
        assertThat(reasonOf(ImpliedPoissonModel.Infeasibility.LAMBDA_SPLIT_IMPOSSIBLE, 0.0))
                .isEqualTo("LAMBDA_SPLIT_IMPOSSIBLE");
        assertThat(reasonOf(ImpliedPoissonModel.Infeasibility.TOTALS_PROBABILITY_OUT_OF_RANGE, 0.0))
                .isEqualTo("TOTALS_PROBABILITY_OUT_OF_RANGE");
        assertThat(reasonOf(ImpliedPoissonModel.Infeasibility.HANDICAP_PROBABILITY_OUT_OF_RANGE, 0.0))
                .isEqualTo("HANDICAP_PROBABILITY_OUT_OF_RANGE");
        assertThat(reasonOf(ImpliedPoissonModel.Infeasibility.INVALID_INPUT, 0.0))
                .isEqualTo("INVALID_INPUT");

        // 反解可行、只是残差超限：必须留空。写成 NONE 会让「市场打架」混进
        // 「搜索域不够宽」那一桶，而这两件事的修法相反。
        assertThat(reasonOf(ImpliedPoissonModel.Infeasibility.NONE, 0.05)).isNull();
    }

    @Test
    void leavesLambdaAndResidualNullWhenSolveIsInfeasible() {
        // 不可行解的 λ 是 0 占位、残差是 +∞。落库会把「平均 λ」拉到 0，
        // 而 max_residual 是 double precision，写无穷大直接整批失败。
        BacktestCaseEntity entity = BacktestCaseBuilder.build(
                1L, "g", CANDIDATE, DECISION_AT, 1L,
                decision(BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS,
                        null, null, null,
                        solution(ImpliedPoissonModel.Infeasibility.LAMBDA_SPLIT_IMPOSSIBLE, 0.0),
                        null, null));

        assertThat(entity.getLambdaHome()).isNull();
        assertThat(entity.getLambdaAway()).isNull();
        assertThat(entity.getMaxResidual()).isNull();
        assertThat(entity.getInfeasibilityReason()).isEqualTo("LAMBDA_SPLIT_IMPOSSIBLE");
    }

    @Test
    void leavesInfeasibilityReasonNullWhenSolutionIsAbsent() {
        AsianDecision decision = decision(
                BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS,
                null, null, null, null, null, null);

        assertThat(BacktestCaseBuilder.build(1L, "g", CANDIDATE, DECISION_AT, 1L, decision)
                .getInfeasibilityReason()).isNull();
    }

    private static String reasonOf(ImpliedPoissonModel.Infeasibility infeasibility, double residual) {
        AsianDecision decision = decision(
                BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS,
                null, null, null,
                solution(infeasibility, residual),
                null, null);
        return BacktestCaseBuilder.build(1L, "g", CANDIDATE, DECISION_AT, 1L, decision)
                .getInfeasibilityReason();
    }

    private static AsianDecision decision(
            BaselinePredictionSkipReasonEnum skipReason,
            AsianOddsSnapshot frozen,
            LineConsensus handicapConsensus,
            LineConsensus totalsConsensus,
            ImpliedPoissonModel.Solution solution,
            AsianDecision.HandicapLeg handicap,
            AsianDecision.TotalsLeg totals
    ) {
        return decision(skipReason, frozen, handicapConsensus, totalsConsensus, solution, handicap, totals, null);
    }

    private static AsianDecision decision(
            BaselinePredictionSkipReasonEnum skipReason,
            AsianOddsSnapshot frozen,
            LineConsensus handicapConsensus,
            LineConsensus totalsConsensus,
            ImpliedPoissonModel.Solution solution,
            AsianDecision.HandicapLeg handicap,
            AsianDecision.TotalsLeg totals,
            ImpliedPoissonModel.NeighborCheck neighborCheck
    ) {
        return new AsianDecision(
                11L,
                frozen == null ? null : frozen.getCapturedAt(),
                skipReason,
                frozen,
                handicapConsensus,
                totalsConsensus,
                totalsConsensus,
                solution,
                neighborCheck,
                handicap,
                totals
        );
    }

    private static ImpliedPoissonModel.Solution solution(
            ImpliedPoissonModel.Infeasibility infeasibility,
            double residual
    ) {
        return new ImpliedPoissonModel.Solution(1.42, 1.08, residual, residual, infeasibility);
    }

    private static LineConsensus consensus(String firstProbability) {
        BigDecimal first = new BigDecimal(firstProbability);
        return new LineConsensus(
                new BigDecimal("-0.50"),
                8,
                3,
                true,
                first,
                BigDecimal.ONE.subtract(first),
                new BigDecimal("0.0118"),
                new BigDecimal("1.9000"),
                new BigDecimal("2.0000"),
                List.of("pinnacle", "betfair")
        );
    }

    private static AsianPublishGate.Verdict verdict(boolean wouldPublish) {
        return new AsianPublishGate.Verdict(
                ImpliedPoissonModel.Market.HANDICAP,
                ImpliedPoissonModel.Side.FIRST,
                new BigDecimal("0.5200"),
                new BigDecimal("0.5000"),
                new BigDecimal("0.0200"),
                new BigDecimal("0.5263"),
                new BigDecimal("0.5363"),
                new BigDecimal("0.0100"),
                wouldPublish
        );
    }

    private static AsianOddsSnapshot frozenRow() {
        AsianOddsSnapshot snapshot = new AsianOddsSnapshot();
        snapshot.setId(5001L);
        snapshot.setMatchId(11L);
        snapshot.setProviderCode("NOVA_API");
        snapshot.setBookmakerCode("pinnacle");
        snapshot.setHandicapLine(new BigDecimal("-0.50"));
        snapshot.setHomeOdds(new BigDecimal("1.9000"));
        snapshot.setAwayOdds(new BigDecimal("2.0000"));
        snapshot.setTotalLine(new BigDecimal("2.75"));
        snapshot.setOverOdds(new BigDecimal("1.8800"));
        snapshot.setUnderOdds(new BigDecimal("1.9200"));
        snapshot.setCapturedAt(Instant.parse("2026-06-20T08:00:00Z"));
        return snapshot;
    }
}
