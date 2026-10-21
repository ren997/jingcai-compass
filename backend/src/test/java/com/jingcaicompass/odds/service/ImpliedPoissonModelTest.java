package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Infeasibility;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Market;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.NeighborCheck;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Side;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * λ 反解的投影口径、可逆性与邻线校验。
 *
 * <p>手算期望值由 40 位 Decimal 独立复算（λh=1.60、λa=1.10、矩阵上限 24），不是从实现里抄的：
 * 这些数字同时是「三种线型共用一条公式」与「-0.25 不会被误判成半盘」两条不变式的钉子。</p>
 */
class ImpliedPoissonModelTest {

    private static final double LAMBDA_HOME = 1.60d;
    private static final double LAMBDA_AWAY = 1.10d;
    private static final double TOLERANCE = 1e-9d;
    private static final double TRAVEL_TOLERANCE = 1e-4d;

    private final ImpliedPoissonModel model = new ImpliedPoissonModel(AsianModelProperties.defaults());

    // ---------- 三种线型的投影口径 ----------

    @Test
    void halfLineHandicapMatchesHandComputedProbability() {
        // 半盘无走盘，p_eff 就是赢盘概率本身。
        assertThat(project("-0.5", Market.HANDICAP)).isCloseTo(0.489573537d, within());
    }

    @Test
    void integerLineHandicapConditionsOutPush() {
        // 平手盘有走盘，分母必须剔除走盘而不是把走盘按 0.5 计入。
        assertThat(project("0", Market.HANDICAP)).isCloseTo(0.651818259d, within());
    }

    @Test
    void quarterLineHandicapMergesHalfLoss() {
        // -0.25 拆 0 与 -0.5：比分打平时 0 走盘、-0.5 输，合并为半输。
        assertThat(project("-0.25", Market.HANDICAP)).isCloseTo(0.559164648d, within());
    }

    @Test
    void quarterLineHandicapMergesHalfWin() {
        // -0.75 拆 -1.0 与 -0.5：主队净胜一球时 -1.0 走盘、-0.5 赢，合并为半赢。
        assertThat(project("-0.75", Market.HANDICAP)).isCloseTo(0.421822029d, within());
    }

    @Test
    void integerLineTotalsConditionsOutPush() {
        assertThat(project("2.0", Market.TOTALS)).isCloseTo(0.670664143d, within());
    }

    @Test
    void quarterLineTotalsMergesHalfLoss() {
        // 2.25 拆 2.0 与 2.5：恰好 2 球时 2.0 走盘、2.5 输，合并为半输。
        assertThat(project("2.25", Market.TOTALS)).isCloseTo(0.577054301d, within());
    }

    @Test
    void halfLineTotalsMatchesHandComputedProbability() {
        assertThat(project("2.5", Market.TOTALS)).isCloseTo(0.506375509d, within());
    }

    @Test
    void quarterLineTotalsMergesHalfWin() {
        // 2.75 拆 2.5 与 3.0：恰好 3 球时 2.5 赢、3.0 走盘，合并为半赢。
        assertThat(project("2.75", Market.TOTALS)).isCloseTo(0.445219975d, within());
    }

    @Test
    void twoSidesSumToOne() {
        // 首边与次边互补是零 EV 口径的核心性质；若拆盘系数写错，和为 1 会立刻破。
        String[] handicapLines = {"-2.5", "-1.25", "-0.75", "-0.5", "-0.25", "0", "0.25", "0.5", "1.5", "2.25"};
        for (String line : handicapLines) {
            double first = project(line, Market.HANDICAP);
            double second = model.project(LAMBDA_HOME, LAMBDA_AWAY, new BigDecimal(line), Market.HANDICAP, Side.SECOND);
            assertThat(first + second).as("handicap %s", line).isCloseTo(1.0d, within(1e-12d));
        }
        String[] totalsLines = {"0.5", "1.25", "2.0", "2.75", "3.5", "4.25"};
        for (String line : totalsLines) {
            double first = project(line, Market.TOTALS);
            double second = model.project(LAMBDA_HOME, LAMBDA_AWAY, new BigDecimal(line), Market.TOTALS, Side.SECOND);
            assertThat(first + second).as("totals %s", line).isCloseTo(1.0d, within(1e-12d));
        }
    }

    @Test
    void totalsProjectionDependsOnlyOnLambdaSum() {
        // 独立泊松之和仍是泊松：这是把二维求解降为一维二分的数学依据，不是近似。
        BigDecimal line = new BigDecimal("2.75");
        double base = model.project(1.6d, 1.1d, line, Market.TOTALS, Side.FIRST);
        assertThat(model.project(1.9d, 0.8d, line, Market.TOTALS, Side.FIRST)).isCloseTo(base, within(1e-12d));
        assertThat(model.project(0.4d, 2.3d, line, Market.TOTALS, Side.FIRST)).isCloseTo(base, within(1e-12d));
    }

    // ---------- 可逆性 ----------

    @Test
    void roundTripRecoversLambdaWithinTolerance() {
        double[][] targets = {
                {1.60d, 1.10d},
                {0.75d, 0.60d},
                {0.20d, 0.20d},
                {3.90d, 3.80d},
                {0.25d, 3.90d},
                {3.80d, 0.30d}
        };
        Object[][] lines = {
                {new BigDecimal("-0.25"), new BigDecimal("2.75")},
                {new BigDecimal("0"), new BigDecimal("2.50")},
                {new BigDecimal("0.25"), new BigDecimal("0.75")},
                {new BigDecimal("-2.50"), new BigDecimal("6.50")},
                {new BigDecimal("-3.25"), new BigDecimal("4.25")},
                {new BigDecimal("3.25"), new BigDecimal("4.25")}
        };
        for (int i = 0; i < targets.length; i++) {
            BigDecimal handicapLine = (BigDecimal) lines[i][0];
            BigDecimal totalsLine = (BigDecimal) lines[i][1];
            double home = targets[i][0];
            double away = targets[i][1];
            double cover = model.project(home, away, handicapLine, Market.HANDICAP, Side.FIRST);
            double over = model.project(home, away, totalsLine, Market.TOTALS, Side.FIRST);

            ImpliedPoissonModel.Solution solution = model.solve(handicapLine, cover, totalsLine, over);

            assertThat(solution.infeasibility()).as("case %d", i).isEqualTo(Infeasibility.NONE);
            assertThat(solution.lambdaHome()).as("λh of case %d", i).isCloseTo(home, within(TRAVEL_TOLERANCE));
            assertThat(solution.lambdaAway()).as("λa of case %d", i).isCloseTo(away, within(TRAVEL_TOLERANCE));
            assertThat(solution.coverResidual()).as("cover residual of case %d", i).isLessThan(TRAVEL_TOLERANCE);
            assertThat(solution.overResidual()).as("over residual of case %d", i).isLessThan(TRAVEL_TOLERANCE);
        }
    }

    @Test
    void infeasibleWhenTotalsProbabilityOutOfRange() {
        // 大球概率 0.99 在 9.0 的线上需要 λ 总和远超 8.0 的搜索上界。
        ImpliedPoissonModel.Solution solution = model.solve(
                new BigDecimal("-0.25"), 0.5d, new BigDecimal("9.0"), 0.99d);

        assertThat(solution.feasible()).isFalse();
        assertThat(solution.infeasibility()).isEqualTo(Infeasibility.TOTALS_PROBABILITY_OUT_OF_RANGE);
        assertThat(solution.maxResidual()).isPositive().isInfinite();
    }

    @Test
    void infeasibleWhenCoverProbabilityOutOfRange() {
        // 总和锁死在 2.7 附近时，主队赢盘概率的上限远达不到 0.99。
        double over = model.project(1.35d, 1.35d, new BigDecimal("2.5"), Market.TOTALS, Side.FIRST);
        ImpliedPoissonModel.Solution solution = model.solve(
                new BigDecimal("-0.25"), 0.99d, new BigDecimal("2.5"), over);

        assertThat(solution.infeasibility()).isEqualTo(Infeasibility.HANDICAP_PROBABILITY_OUT_OF_RANGE);
    }

    @Test
    void rejectsInvalidProbabilityInput() {
        assertThat(model.solve(new BigDecimal("-0.25"), 0d, new BigDecimal("2.5"), 0.5d).infeasibility())
                .isEqualTo(Infeasibility.INVALID_INPUT);
        assertThat(model.solve(null, 0.5d, new BigDecimal("2.5"), 0.5d).infeasibility())
                .isEqualTo(Infeasibility.INVALID_INPUT);
    }

    // ---------- 邻线校验 ----------

    @Test
    void neighborCheckSkipsMainLineAndSingleBookmakerLines() {
        List<AsianMarketConsensusAggregator.LineConsensus> handicap = List.of(
                consensus("-0.25", 4, 0.5100d),
                consensus("-0.50", 3, 0.4435d),
                consensus("-0.75", 1, 0.3799d));
        List<AsianMarketConsensusAggregator.LineConsensus> totals = List.of(
                consensus("2.75", 3, 0.4755d),
                consensus("3.00", 3, 0.4096d));

        NeighborCheck check = model.check(
                1.5656d, 1.2487d, handicap, new BigDecimal("-0.25"), totals, new BigDecimal("2.75"));

        // 让球只剩 -0.50（-0.25 是主盘、-0.75 只有 1 家），大小球只剩 3.00，共 2 档。
        assertThat(check.lineCount()).isEqualTo(2);
        assertThat(check.validated()).isTrue();
        assertThat(check.maxDeviation()).isLessThan(0.040d);
    }

    @Test
    void neighborCheckDetectsInconsistentLine() {
        // 主盘两方程自洽，但邻线被人为偏离：邻线校验必须抓到。
        double home = 1.60d;
        double away = 1.10d;
        double cover = model.project(home, away, new BigDecimal("-0.25"), Market.HANDICAP, Side.FIRST);
        double over = model.project(home, away, new BigDecimal("2.75"), Market.TOTALS, Side.FIRST);
        double neighborTruth = model.project(home, away, new BigDecimal("0.50"), Market.HANDICAP, Side.FIRST);

        List<AsianMarketConsensusAggregator.LineConsensus> handicap = List.of(
                consensus("-0.25", 4, cover),
                consensus("0.50", 3, neighborTruth + 0.08d));

        NeighborCheck check = model.check(
                home, away, handicap, new BigDecimal("-0.25"), List.of(), null);

        assertThat(check.lineCount()).isEqualTo(1);
        // 夹具把共识概率截到 6 位，偏差会带 ~5e-7 的量化误差，故容差取 1e-5 而不是 1e-9。
        assertThat(check.maxDeviation()).isCloseTo(0.08d, within(1e-5d));
        assertThat(check.within(new BigDecimal("0.0400"))).isFalse();
        assertThat(check.worstMarket()).isEqualTo(Market.HANDICAP);
        assertThat(check.worstLine()).isEqualByComparingTo("0.50");
    }

    @Test
    void neighborCheckWithoutEligibleLinesDoesNotPretendToBeValidated() {
        NeighborCheck check = model.check(
                1.6d, 1.1d, List.of(consensus("-0.25", 4, 0.51d)), new BigDecimal("-0.25"), List.of(), null);

        assertThat(check.lineCount()).isZero();
        assertThat(check.validated()).isFalse();
        // 无邻线时不拦截，但调用方必须能从 lineCount 区分「没校验」与「校验通过」。
        assertThat(check.within(new BigDecimal("0.0400"))).isTrue();
    }

    // ---------- 截断与归一化 ----------

    @Test
    void poissonPmfIsNormalizedEvenAtUpperBound() {
        double[] pmf = model.poissonPmf(ImpliedPoissonModel.MAX_LAMBDA, 24);
        double sum = 0d;
        for (double mass : pmf) {
            sum += mass;
        }
        assertThat(sum).isCloseTo(1.0d, within(1e-15d));
        assertThat(pmf).hasSize(25);
    }

    private double project(String line, Market market) {
        return model.project(LAMBDA_HOME, LAMBDA_AWAY, new BigDecimal(line), market, Side.FIRST);
    }

    private static AsianMarketConsensusAggregator.LineConsensus consensus(String line, int sampleCount, double first) {
        BigDecimal probability = BigDecimal.valueOf(first).setScale(AsianMarketMath.PROBABILITY_SCALE,
                java.math.RoundingMode.HALF_UP);
        return new AsianMarketConsensusAggregator.LineConsensus(
                new BigDecimal(line),
                sampleCount,
                0,
                false,
                probability,
                BigDecimal.ONE.subtract(probability),
                BigDecimal.ZERO,
                AsianMarketMath.fairPrice(probability),
                AsianMarketMath.fairPrice(BigDecimal.ONE.subtract(probability)),
                List.of("test"));
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return within(TOLERANCE);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
