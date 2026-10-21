package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Infeasibility;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Market;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.NeighborCheck;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Side;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 C 验收：用 3 场 NovaAPI 真实抓包验证 λ 反解与邻线校验。
 *
 * <p>基线数值由独立的 40 位 Decimal 原型先行算出，Java 实现与之差在 1e-3 以内（差异来自聚合器的
 * 6 位概率舍入），两条实现互为交叉验证。三场主盘全是 -0.25，正好压住「四分之一盘被误判成半盘」
 * 这条最容易出错的路径。</p>
 */
class ImpliedPoissonFixtureTest {

    /** 主盘线与 λ 的容差：聚合器把概率截到 6 位，反解结果相应有 1e-3 量级漂移。 */
    private static final double LAMBDA_TOLERANCE = 0.005d;

    private final AsianMarketConsensusAggregator aggregator =
            new AsianMarketConsensusAggregator(AsianOddsAggregationProperties.defaults());
    private final ImpliedPoissonModel model =
            new ImpliedPoissonModel(AsianModelProperties.defaults());

    @ParameterizedTest(name = "{0} → λh={2} λa={3}")
    @CsvSource({
            "asianodds/novaapi-2492881-tf-odds.json, 1.5656, 1.2487",
            "asianodds/novaapi-2407136-tf-odds.json, 1.7638, 1.5667",
            "asianodds/novaapi-2594567-tf-odds.json, 1.7237, 1.3792"
    })
    void solvesRealFixturesWithinTolerance(String resource, double expectedHome, double expectedAway) {
        Solved solved = solve(resource);

        assertThat(solved.solution().infeasibility()).isEqualTo(Infeasibility.NONE);
        assertThat(solved.solution().lambdaHome()).isCloseTo(expectedHome, offset(LAMBDA_TOLERANCE));
        assertThat(solved.solution().lambdaAway()).isCloseTo(expectedAway, offset(LAMBDA_TOLERANCE));
        // 恰定方程组的残差只反映二分精度，不反映市场分歧——这一点必须留在断言里，否则后人会
        // 误以为残差大就代表市场矛盾。
        assertThat(solved.solution().maxResidual()).isLessThan(1e-4d);
    }

    @ParameterizedTest(name = "{0} → 邻线 {1} 档最大 {2}")
    @CsvSource({
            "asianodds/novaapi-2492881-tf-odds.json, 23, 0.02705",
            "asianodds/novaapi-2407136-tf-odds.json, 21, 0.02057",
            "asianodds/novaapi-2594567-tf-odds.json, 30, 0.03508"
    })
    void neighborDeviationsMatchRecordedValues(String resource, int expectedLines, double expectedDeviation) {
        Solved solved = solve(resource);
        NeighborCheck check = solved.check();

        assertThat(check.validated()).isTrue();
        // 档数与偏差同时钉住：任何「校验静默失效」（误把主盘或单家庄线算进去）都会先破这里。
        assertThat(check.lineCount()).isEqualTo(expectedLines);
        assertThat(check.maxDeviation()).isCloseTo(expectedDeviation, offset(1e-3d));
        assertThat(check.within(new BigDecimal("0.0400")))
                .as("%s：邻线偏差必须在阈值内", resource)
                .isTrue();
    }

    @Test
    void everyProbeEventStaysBelowTheNeighborTolerance() {
        for (String resource : List.of(
                NovaApiOddsFixture.EVENT_2492881,
                NovaApiOddsFixture.EVENT_2407136,
                NovaApiOddsFixture.EVENT_2594567)) {
            Solved solved = solve(resource);
            assertThat(solved.check().within(new BigDecimal("0.0400")))
                    .as("%s：实测最大偏差应留有余量，否则阈值定得过紧", resource)
                    .isTrue();
            // 阈值不能松到失去意义：拿实测最大偏差 3.508% 反推，阈值不应超过 5%。
            assertThat(solved.check().maxDeviation()).isLessThan(0.05d);
        }
    }

    @Test
    void singlePriceAtOneIsExcludedFromNeighborValidation() {
        // 2492881 的大小球 5.50 / 7.50 档，小球报价恰为 1.00。价格必须严格大于 1.00 才可去水，
        // 因此这两档只剩一家，不能参与邻线校验——把 1.00 当可用会把校验档数从 23 抬到 25 并
        // 引入一个并不存在的错价。
        Solved solved = solve(NovaApiOddsFixture.EVENT_2492881);

        assertThat(solved.check().lineCount()).isEqualTo(23);
        assertThat(solved.totalsLines()).doesNotContain(new BigDecimal("5.50"), new BigDecimal("7.50"));
    }

    @Test
    void mainLineRoundTripIsExactForQuarterLineFixtures() {
        // 三场的让球主盘都是 -0.25，其中两场大小球主盘是 x.75/x.25 的四分之一盘，
        // 因此这条用例实质是在真实数据上验证拆盘投影没有丢概率。
        for (String resource : List.of(
                NovaApiOddsFixture.EVENT_2492881,
                NovaApiOddsFixture.EVENT_2407136,
                NovaApiOddsFixture.EVENT_2594567)) {
            Solved solved = solve(resource);
            double home = solved.solution().lambdaHome();
            double away = solved.solution().lambdaAway();

            double coverAgain = model.project(home, away, solved.handicapLine(), Market.HANDICAP, Side.FIRST);
            double overAgain = model.project(home, away, solved.totalsLine(), Market.TOTALS, Side.FIRST);

            assertThat(coverAgain).as("%s：让球主盘回投", resource)
                    .isCloseTo(solved.coverProbability(), offset(1e-4d));
            assertThat(overAgain).as("%s：大小球主盘回投", resource)
                    .isCloseTo(solved.overProbability(), offset(1e-4d));
        }
    }

    private Solved solve(String resource) {
        NovaApiOddsFixture.Event event = NovaApiOddsFixture.load(resource);
        AsianMarketConsensusAggregator.MarketAggregate handicapAggregate =
                aggregator.aggregate(event.handicap());
        AsianMarketConsensusAggregator.MarketAggregate totalsAggregate =
                aggregator.aggregate(event.totals());
        AsianMarketConsensusAggregator.LineConsensus handicapBest = handicapAggregate.best().orElseThrow();
        AsianMarketConsensusAggregator.LineConsensus totalsBest = totalsAggregate.best().orElseThrow();

        ImpliedPoissonModel.Solution solution = model.solve(
                handicapBest.line(),
                handicapBest.firstProbability().doubleValue(),
                totalsBest.line(),
                totalsBest.firstProbability().doubleValue());
        NeighborCheck check = model.check(
                solution.lambdaHome(),
                solution.lambdaAway(),
                handicapAggregate.ranked(),
                handicapBest.line(),
                totalsAggregate.ranked(),
                totalsBest.line());
        return new Solved(
                solution,
                check,
                handicapBest.line(),
                totalsBest.line(),
                handicapBest.firstProbability().doubleValue(),
                totalsBest.firstProbability().doubleValue(),
                totalsAggregate.ranked().stream()
                        .map(AsianMarketConsensusAggregator.LineConsensus::line)
                        .toList());
    }

    private static org.assertj.core.data.Offset<Double> offset(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    private record Solved(
            ImpliedPoissonModel.Solution solution,
            NeighborCheck check,
            BigDecimal handicapLine,
            BigDecimal totalsLine,
            double coverProbability,
            double overProbability,
            List<BigDecimal> totalsLines
    ) {
    }
}
