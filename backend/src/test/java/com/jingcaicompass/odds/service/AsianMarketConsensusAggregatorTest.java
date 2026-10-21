package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨庄去水共识聚合：顺序、去重、白名单回退与主盘选择。 */
class AsianMarketConsensusAggregatorTest {

    private static final Instant OLDER = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-10-20T11:00:00Z");

    private final AsianMarketConsensusAggregator aggregator =
            new AsianMarketConsensusAggregator(AsianOddsAggregationProperties.defaults());

    @Test
    void devigThenMedianMatchesHandComputedConsensus() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "0", "1.90", "1.95", OLDER, 1L),
                quote("bet365", "0", "1.90", "1.95", OLDER, 2L),
                quote("williamhill", "0", "1.90", "1.95", OLDER, 3L),
                quote("1xbet", "0", "1.50", "2.60", OLDER, 4L));

        AsianMarketConsensusAggregator.LineConsensus consensus =
                aggregator.aggregate(quotes).best().orElseThrow();

        // 三家一致报 1.90/1.95（去水 0.506494），第四家为离群报价，中位数应完全跟随多数。
        assertThat(consensus.firstProbability()).isEqualByComparingTo("0.506494");
        assertThat(consensus.sampleCount()).isEqualTo(4);
        assertThat(consensus.dispersion()).isGreaterThan(new BigDecimal("0.05"));
    }

    @Test
    void choosesBalancedLineOverModalLine() {
        List<AsianMarketConsensusAggregator.Quote> quotes = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            quotes.add(quote("book" + index, "-1.5", "1.72", "2.12", OLDER, (long) index));
        }
        quotes.add(quote("pinnacle", "-1.25", "1.92", "1.94", OLDER, 100L));
        quotes.add(quote("bet365", "-1.25", "1.92", "1.94", OLDER, 101L));

        AsianMarketConsensusAggregator.LineConsensus best =
                aggregator.aggregate(quotes).best().orElseThrow();

        // 众数在 -1.5（7 家），但真盘是最平衡的 -1.25（2 家）。
        assertThat(best.line()).isEqualByComparingTo("-1.25");
        assertThat(best.sampleCount()).isEqualTo(2);
        assertThat(best.imbalance()).isLessThan(new BigDecimal("0.003"));
    }

    @Test
    void halfLineWinsExactTieOverIntegerLine() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "-1", "1.90", "1.95", OLDER, 1L),
                quote("bet365", "-1", "1.90", "1.95", OLDER, 2L),
                quote("pinnacle", "-0.5", "1.90", "1.95", OLDER, 3L),
                quote("bet365", "-0.5", "1.90", "1.95", OLDER, 4L));

        AsianMarketConsensusAggregator.MarketAggregate aggregate = aggregator.aggregate(quotes);

        assertThat(aggregate.ranked()).hasSize(2);
        assertThat(aggregate.ranked().get(0).imbalance())
                .isEqualByComparingTo(aggregate.ranked().get(1).imbalance());
        assertThat(aggregate.best().orElseThrow().line()).isEqualByComparingTo("-0.5");
    }

    @Test
    void quarterLineWinsExactTieOverIntegerLine() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "-1", "1.90", "1.95", OLDER, 1L),
                quote("bet365", "-1", "1.90", "1.95", OLDER, 2L),
                quote("pinnacle", "-0.25", "1.90", "1.95", OLDER, 3L),
                quote("bet365", "-0.25", "1.90", "1.95", OLDER, 4L));

        assertThat(aggregator.aggregate(quotes).best().orElseThrow().line())
                .isEqualByComparingTo("-0.25");
    }

    @Test
    void tieBreakDoesNotOverrideSmallerImbalance() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "-0.5", "1.90", "1.97", OLDER, 1L),
                quote("bet365", "-0.5", "1.90", "1.97", OLDER, 2L),
                quote("pinnacle", "-1", "1.90", "1.95", OLDER, 3L),
                quote("bet365", "-1", "1.90", "1.95", OLDER, 4L));

        // 半球线型更受偏好，但整数盘更平衡 0.0026；任何容差都会选错。
        assertThat(aggregator.aggregate(quotes).best().orElseThrow().line())
                .isEqualByComparingTo("-1");
    }

    @Test
    void usesWhitelistMedianWhenTwoWhitelistedQuotesExist() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "0", "1.98", "1.92", OLDER, 1L),
                quote("matchbook", "0", "1.98", "1.92", OLDER, 2L),
                quote("bet365", "0", "1.85", "2.05", OLDER, 3L),
                quote("1xbet", "0", "1.84", "2.06", OLDER, 4L));

        AsianMarketConsensusAggregator.LineConsensus consensus =
                aggregator.aggregate(quotes).best().orElseThrow();

        assertThat(consensus.whitelistApplied()).isTrue();
        assertThat(consensus.sampleCount()).isEqualTo(2);
        assertThat(consensus.whitelistCount()).isEqualTo(2);
        assertThat(consensus.firstProbability()).isEqualByComparingTo("0.492308");
        assertThat(consensus.sampleSource()).startsWith("白名单");
    }

    @Test
    void fallsBackToAllWhenWhitelistHasOneQuote() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "0", "1.98", "1.92", OLDER, 1L),
                quote("bet365", "0", "1.85", "2.05", OLDER, 2L),
                quote("williamhill", "0", "1.86", "2.04", OLDER, 3L),
                quote("1xbet", "0", "1.87", "2.03", OLDER, 4L));

        AsianMarketConsensusAggregator.LineConsensus consensus =
                aggregator.aggregate(quotes).best().orElseThrow();

        assertThat(consensus.whitelistApplied()).isFalse();
        assertThat(consensus.sampleCount()).isEqualTo(4);
        assertThat(consensus.whitelistCount()).isEqualTo(1);
        assertThat(consensus.firstProbability()).isEqualByComparingTo("0.521795");
        assertThat(consensus.sampleSource()).startsWith("回退全量");
    }

    @Test
    void dropsLineWithSingleBookmaker() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "-0.5", "1.90", "1.95", OLDER, 1L),
                quote("bet365", "-1", "1.90", "1.95", OLDER, 2L),
                quote("williamhill", "-1", "1.90", "1.95", OLDER, 3L));

        AsianMarketConsensusAggregator.MarketAggregate aggregate = aggregator.aggregate(quotes);

        assertThat(aggregate.ranked()).hasSize(1);
        assertThat(aggregate.best().orElseThrow().line()).isEqualByComparingTo("-1");
    }

    @Test
    void returnsEmptyWhenEveryLineIsTooThin() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "-0.5", "1.90", "1.95", OLDER, 1L),
                quote("bet365", "-0.75", "1.90", "1.95", OLDER, 2L),
                quote("williamhill", "-1", "1.90", "1.95", OLDER, 3L));

        assertThat(aggregator.aggregate(quotes).isEmpty()).isTrue();
    }

    @Test
    void returnsEmptyForNullOrEmptyInput() {
        assertThat(aggregator.aggregate(null).isEmpty()).isTrue();
        assertThat(aggregator.aggregate(List.of()).isEmpty()).isTrue();
    }

    @Test
    void deduplicatesSameBookmakerAcrossProvidersKeepingLatestQuote() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                new AsianMarketConsensusAggregator.Quote(
                        "the-odds-api", "Pinnacle", new BigDecimal("0"), new BigDecimal("1.90"),
                        new BigDecimal("1.95"), OLDER, 1L),
                new AsianMarketConsensusAggregator.Quote(
                        "novaapi", "pinnacle", new BigDecimal("0.00"), new BigDecimal("1.80"),
                        new BigDecimal("2.10"), NEWER, 2L),
                quote("bet365", "0", "1.90", "1.95", OLDER, 3L));

        AsianMarketConsensusAggregator.LineConsensus consensus =
                aggregator.aggregate(quotes).best().orElseThrow();

        assertThat(consensus.sampleCount()).isEqualTo(2);
        // 只保留 pinnalce 的较新报价（1.80/2.10 → 0.538462）与 bet365（0.506494）的中位数。
        assertThat(consensus.firstProbability()).isEqualByComparingTo("0.522478");
    }

    @Test
    void ignoresPricesAtOrBelowOne() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "0", "1.90", "1.95", OLDER, 1L),
                quote("bet365", "0", "1.90", "1.95", OLDER, 2L),
                quote("1xbet", "0", "1.00", "51.00", OLDER, 3L));

        AsianMarketConsensusAggregator.LineConsensus consensus =
                aggregator.aggregate(quotes).best().orElseThrow();

        assertThat(consensus.sampleCount()).isEqualTo(2);
        assertThat(consensus.bookmakers()).containsExactly("bet365", "pinnacle");
    }

    @Test
    void exposesDispersionAndFairPrices() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "0", "1.88", "1.97", OLDER, 1L),
                quote("bet365", "0", "1.92", "1.93", OLDER, 2L),
                quote("williamhill", "0", "1.85", "2.00", OLDER, 3L));

        AsianMarketConsensusAggregator.LineConsensus consensus =
                aggregator.aggregate(quotes).best().orElseThrow();

        assertThat(consensus.firstProbability()).isEqualByComparingTo("0.511688");
        assertThat(consensus.secondProbability()).isEqualByComparingTo("0.488312");
        assertThat(consensus.fairFirstPrice()).isEqualByComparingTo("1.9543");
        assertThat(consensus.dispersion()).isPositive();
        assertThat(consensus.favorsFirstSide()).isTrue();
    }

    @Test
    void atLineFindsConsensusForNonBestLine() {
        List<AsianMarketConsensusAggregator.Quote> quotes = List.of(
                quote("pinnacle", "-0.5", "1.90", "1.95", OLDER, 1L),
                quote("bet365", "-0.5", "1.90", "1.95", OLDER, 2L),
                quote("pinnacle", "2.5", "1.72", "2.12", OLDER, 3L),
                quote("bet365", "2.5", "1.72", "2.12", OLDER, 4L));

        AsianMarketConsensusAggregator.MarketAggregate aggregate = aggregator.aggregate(quotes);

        assertThat(aggregate.best().orElseThrow().line()).isEqualByComparingTo("-0.5");
        assertThat(aggregate.atLine(new BigDecimal("2.50")).orElseThrow().firstProbability())
                .isEqualByComparingTo("0.552083");
        assertThat(aggregate.atLine(new BigDecimal("1.5"))).isEmpty();
        assertThat(aggregate.atLine(null)).isEmpty();
    }

    private AsianMarketConsensusAggregator.Quote quote(
            String bookmaker,
            String line,
            String firstPrice,
            String secondPrice,
            Instant capturedAt,
            Long snapshotId
    ) {
        return new AsianMarketConsensusAggregator.Quote(
                "the-odds-api",
                bookmaker,
                new BigDecimal(line),
                new BigDecimal(firstPrice),
                new BigDecimal(secondPrice),
                capturedAt,
                snapshotId
        );
    }
}
