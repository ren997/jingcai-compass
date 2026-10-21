package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 B 验收：用 3 场 NovaAPI 真实抓包验证「最平衡线 == 平博自身最平衡线」。
 *
 * <p>三场抓包里白名单庄都只有平博一家，因此这里同时验证的是「白名单不足 → 回退全量」路径；
 * 白名单中位数路径由 {@link AsianMarketConsensusAggregatorTest} 用合成样本覆盖。</p>
 */
class AsianMarketConsensusFixtureTest {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final AsianMarketConsensusAggregator aggregator =
            new AsianMarketConsensusAggregator(AsianOddsAggregationProperties.defaults());

    @Test
    void handicapConsensusEqualsPinnacleMostBalancedLine() {
        assertBestLine(NovaApiOddsFixture.EVENT_2492881, false, "-0.25", 4);
        assertBestLine(NovaApiOddsFixture.EVENT_2407136, false, "-0.25", 4);
        assertBestLine(NovaApiOddsFixture.EVENT_2594567, false, "-0.25", 5);
    }

    @Test
    void totalsConsensusEqualsPinnacleMostBalancedLine() {
        assertBestLine(NovaApiOddsFixture.EVENT_2492881, true, "2.75", 3);
        assertBestLine(NovaApiOddsFixture.EVENT_2407136, true, "3.25", 3);
        assertBestLine(NovaApiOddsFixture.EVENT_2594567, true, "3.0", 4);
    }

    @Test
    void everyProbeEventFallsBackToFullSampleOnTheBestLine() {
        for (String resource : List.of(
                NovaApiOddsFixture.EVENT_2492881,
                NovaApiOddsFixture.EVENT_2407136,
                NovaApiOddsFixture.EVENT_2594567)) {
            NovaApiOddsFixture.Event event = NovaApiOddsFixture.load(resource);
            for (List<AsianMarketConsensusAggregator.Quote> quotes :
                    List.of(event.handicap(), event.totals())) {
                AsianMarketConsensusAggregator.LineConsensus best =
                        aggregator.aggregate(quotes).best().orElseThrow();
                assertThat(best.whitelistApplied())
                        .as("%s：最优线上应只有一家白名单庄", resource)
                        .isFalse();
                assertThat(best.whitelistCount()).isEqualTo(1);
            }
        }
    }

    @Test
    void e1TotalsRunnerUpIsWithinThreeThousandths() {
        NovaApiOddsFixture.Event event = NovaApiOddsFixture.load(NovaApiOddsFixture.EVENT_2492881);
        List<AsianMarketConsensusAggregator.LineConsensus> ranked =
                aggregator.aggregate(event.totals()).ranked();

        assertThat(ranked.get(0).line()).isEqualByComparingTo("2.75");
        assertThat(ranked.get(1).line()).isEqualByComparingTo("2.5");
        BigDecimal gap = ranked.get(1).imbalance().subtract(ranked.get(0).imbalance());
        // 两条线只差 0.0028：任何「近似相等就优先半球」的容差都会把这场判到 2.5，偏离平博主盘。
        assertThat(gap).isBetween(new BigDecimal("0.0001"), new BigDecimal("0.003"));
    }

    @Test
    void dispersionIsPositiveForEveryMarket() {
        for (String resource : List.of(
                NovaApiOddsFixture.EVENT_2492881,
                NovaApiOddsFixture.EVENT_2407136,
                NovaApiOddsFixture.EVENT_2594567)) {
            NovaApiOddsFixture.Event event = NovaApiOddsFixture.load(resource);
            for (List<AsianMarketConsensusAggregator.Quote> quotes :
                    List.of(event.handicap(), event.totals())) {
                AsianMarketConsensusAggregator.LineConsensus best =
                        aggregator.aggregate(quotes).best().orElseThrow();
                assertThat(best.dispersion()).isPositive();
                assertThat(best.firstProbability().add(best.secondProbability()))
                        .isEqualByComparingTo(BigDecimal.ONE);
                assertThat(best.imbalance()).isLessThan(HALF);
            }
        }
    }

    private void assertBestLine(String resource, boolean totals, String expectedLine, int expectedSample) {
        NovaApiOddsFixture.Event event = NovaApiOddsFixture.load(resource);
        List<AsianMarketConsensusAggregator.Quote> quotes = totals ? event.totals() : event.handicap();

        AsianMarketConsensusAggregator.LineConsensus best =
                aggregator.aggregate(quotes).best().orElseThrow();
        BigDecimal pinnacleLine = NovaApiOddsFixture.mostBalancedLine(quotes, "pinnacle").orElseThrow();

        assertThat(best.line()).as("%s：聚合主盘", resource).isEqualByComparingTo(expectedLine);
        assertThat(best.line()).as("%s：应等于平博自身最平衡线", resource)
                .isEqualByComparingTo(pinnacleLine);
        assertThat(best.sampleCount()).as("%s：参与家数", resource).isEqualTo(expectedSample);
    }
}
