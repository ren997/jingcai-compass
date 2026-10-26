package com.jingcaicompass.backtest.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.ImpliedPoissonModel;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.service.AsianDecision;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 判定链停点的推导。
 *
 * <p>这里最重要的是<b>两个共用 {@code INCONSISTENT_ASIAN_MARKETS} 的出口不能合并</b>：
 * 「两条约束无解」意味着参数或市场定价根本不可行，「残差/邻线超限」意味着两个市场各自可行但互相矛盾。
 * 前者该调阈值，后者该怀疑数据源——混成一个数字，调参就会朝错误方向走。</p>
 */
class BacktestDecisionStageEnumTest {

    @Test
    void decidedMeansAtLeastOneMarketHasDirection() {
        assertThat(BacktestDecisionStageEnum.of(decision(null, null))).isEqualTo(BacktestDecisionStageEnum.DECIDED);
    }

    @Test
    void unusableInputRowsAreSeparated() {
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET, null)))
                .isEqualTo(BacktestDecisionStageEnum.MISSING_ROWS);
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.INVALID_ASIAN_MARKET, null)))
                .isEqualTo(BacktestDecisionStageEnum.INVALID_ROWS);
    }

    @Test
    void infeasibleSolutionIsNotInconsistent() {
        // 反解不可行：solution 存在但 feasible() == false。
        AsianDecision infeasible = decision(
                BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS,
                solution(ImpliedPoissonModel.Infeasibility.LAMBDA_SPLIT_IMPOSSIBLE));
        assertThat(BacktestDecisionStageEnum.of(infeasible)).isEqualTo(BacktestDecisionStageEnum.INFEASIBLE);
    }

    @Test
    void residualOrNeighborFailureIsInconsistent() {
        AsianDecision inconsistent = decision(
                BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS,
                solution(ImpliedPoissonModel.Infeasibility.NONE));
        assertThat(BacktestDecisionStageEnum.of(inconsistent)).isEqualTo(BacktestDecisionStageEnum.INCONSISTENT);
    }

    @Test
    void frozenRowFailuresAreSeparated() {
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE, null)))
                .isEqualTo(BacktestDecisionStageEnum.NO_FROZEN_ROW);
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE, null)))
                .isEqualTo(BacktestDecisionStageEnum.FROZEN_TOTALS_LINE_MISMATCH);
    }

    @Test
    void bothMarketsRejectedCoversDispersionAndLowConfidence() {
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.MARKET_DISPERSION_TOO_HIGH, null)))
                .isEqualTo(BacktestDecisionStageEnum.BOTH_MARKETS_REJECTED);
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS, null)))
                .isEqualTo(BacktestDecisionStageEnum.BOTH_MARKETS_REJECTED);
    }

    @Test
    void earlyGatesKeepTheirOwnStage() {
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.NOT_SCHEDULED, null)))
                .isEqualTo(BacktestDecisionStageEnum.NOT_SCHEDULED);
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.KICKOFF_PASSED, null)))
                .isEqualTo(BacktestDecisionStageEnum.KICKOFF_PASSED);
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS, null)))
                .isEqualTo(BacktestDecisionStageEnum.INSUFFICIENT_HANDICAP);
        assertThat(BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_CONSTRAINTS, null)))
                .isEqualTo(BacktestDecisionStageEnum.NO_TOTALS_CONSTRAINT);
    }

    @Test
    void sportterySkipReasonMeansCallerWiredTheWrongPath() {
        // 亚盘回测的输入里没有体彩盘口，出现这个原因说明接错了链路，必须显式炸掉而不是记成某个 stage。
        assertThatThrownBy(() -> BacktestDecisionStageEnum.of(
                decision(BaselinePredictionSkipReasonEnum.MISSING_SPORTTERY_MARKET, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("体彩市场");
    }

    /** 只有 skipReason 为空时才要求带冻结行，因此这里按需构造最小可用的判定结果。 */
    private static AsianDecision decision(
            BaselinePredictionSkipReasonEnum skipReason,
            ImpliedPoissonModel.Solution solution
    ) {
        if (skipReason == null) {
            return new AsianDecision(
                    1L,
                    Instant.parse("2026-06-20T12:00:00Z"),
                    null,
                    new AsianOddsSnapshot(),
                    null,
                    null,
                    null,
                    solution(ImpliedPoissonModel.Infeasibility.NONE),
                    null,
                    new AsianDecision.HandicapLeg(
                            AsianHandicapPickEnum.HOME_COVER,
                            ConfidenceLevelEnum.MEDIUM,
                            false,
                            null,
                            0.52),
                    null
            );
        }
        return new AsianDecision(
                1L,
                null,
                skipReason,
                null,
                null,
                null,
                null,
                solution,
                null,
                null,
                null
        );
    }

    private static ImpliedPoissonModel.Solution solution(ImpliedPoissonModel.Infeasibility infeasibility) {
        return new ImpliedPoissonModel.Solution(1.42, 1.08, 0.0, 0.0, infeasibility);
    }
}
