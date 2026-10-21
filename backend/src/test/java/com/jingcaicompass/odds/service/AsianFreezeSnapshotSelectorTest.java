package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 冻结行选择：必须落在共识线上，并按白名单 → 方向同向 → 大小球对齐 → 抽水 → 时间排序。 */
class AsianFreezeSnapshotSelectorTest {

    private static final Instant OLDER = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-10-20T11:00:00Z");

    private final AsianFreezeSnapshotSelector selector =
            new AsianFreezeSnapshotSelector(AsianOddsAggregationProperties.defaults());

    @Test
    void prefersWhitelistedBookmakerOnConsensusLine() {
        List<AsianOddsSnapshot> rows = List.of(
                row("pinnacle", "-0.25", "1.95", "1.95", "2.75", OLDER, 1L),
                row("bet365", "-0.25", "1.95", "1.95", "2.75", NEWER, 2L));

        assertThat(selector.select(rows, consensus("-0.25", "0.50"), Optional.empty()))
                .map(AsianOddsSnapshot::getBookmakerCode)
                .contains("pinnacle");
    }

    @Test
    void prefersRowWhoseFavoriteSideMatchesConsensus() {
        // 共识看好主队（0.55）；不认同识的行报价更接近公平价，但方向键优先。
        List<AsianOddsSnapshot> rows = List.of(
                row("bet365", "-0.25", "1.95", "1.90", "2.75", NEWER, 2L),
                row("williamhill", "-0.25", "1.50", "2.00", "2.75", OLDER, 1L));

        assertThat(selector.select(rows, consensus("-0.25", "0.55"), Optional.empty()))
                .map(AsianOddsSnapshot::getBookmakerCode)
                .contains("williamhill");
    }

    @Test
    void prefersRowOnTotalsConsensusLine() {
        List<AsianOddsSnapshot> rows = List.of(
                row("bet365", "-0.25", "1.93", "2.08", "3.00", NEWER, 2L),
                row("williamhill", "-0.25", "1.90", "1.95", "2.75", OLDER, 1L));

        assertThat(selector.select(rows, consensus("-0.25", "0.52"), Optional.of(consensus("2.75", "0.48"))))
                .map(AsianOddsSnapshot::getTotalLine)
                .contains(new BigDecimal("2.75"));
    }

    @Test
    void prefersClosestFairPriceWhenHigherKeysTie() {
        List<AsianOddsSnapshot> rows = List.of(
                row("bet365", "-0.25", "1.85", "2.10", "2.75", NEWER, 2L),
                row("williamhill", "-0.25", "1.92", "2.08", "2.75", OLDER, 1L));

        assertThat(selector.select(rows, consensus("-0.25", "0.52"), Optional.empty()))
                .map(AsianOddsSnapshot::getBookmakerCode)
                .contains("williamhill");
    }

    @Test
    void prefersLatestCaptureWhenAllOtherKeysTie() {
        List<AsianOddsSnapshot> rows = List.of(
                row("bet365", "-0.25", "1.92", "2.08", "2.75", OLDER, 9L),
                row("williamhill", "-0.25", "1.92", "2.08", "2.75", NEWER, 1L));

        assertThat(selector.select(rows, consensus("-0.25", "0.52"), Optional.empty()))
                .map(AsianOddsSnapshot::getId)
                .contains(1L);
    }

    @Test
    void rejectsRowWithIncompleteTotals() {
        AsianOddsSnapshot incomplete = row("pinnacle", "-0.25", "1.90", "1.95", "2.75", OLDER, 1L);
        incomplete.setTotalLine(null);
        incomplete.setOverOdds(null);
        incomplete.setUnderOdds(null);

        assertThat(selector.select(List.of(incomplete), consensus("-0.25", "0.5"), Optional.empty()))
                .isEmpty();
    }

    @Test
    void rejectsRowsWithUnusablePrices() {
        AsianOddsSnapshot zeroPrice = row("pinnacle", "-0.25", "1.90", "1.95", "2.75", OLDER, 1L);
        zeroPrice.setAwayOdds(BigDecimal.ZERO);

        assertThat(selector.select(List.of(zeroPrice), consensus("-0.25", "0.5"), Optional.empty()))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenConsensusLineIsNotQuoted() {
        List<AsianOddsSnapshot> rows = List.of(
                row("pinnacle", "-0.5", "1.90", "1.95", "2.75", OLDER, 1L),
                row("bet365", "-1.0", "1.90", "1.95", "2.75", OLDER, 2L));

        assertThat(selector.select(rows, consensus("-0.25", "0.5"), Optional.empty())).isEmpty();
    }

    @Test
    void returnsEmptyForNullOrEmptyInput() {
        assertThat(selector.select(null, consensus("-0.25", "0.5"), Optional.empty())).isEmpty();
        assertThat(selector.select(List.of(), consensus("-0.25", "0.5"), Optional.empty())).isEmpty();
        assertThat(selector.select(List.of(row("pinnacle", "-0.25", "1.9", "1.9", "2.75", OLDER, 1L)),
                null, Optional.empty())).isEmpty();
    }

    private AsianMarketConsensusAggregator.LineConsensus consensus(String line, String firstProbability) {
        BigDecimal first = new BigDecimal(firstProbability);
        BigDecimal second = BigDecimal.ONE.subtract(first);
        return new AsianMarketConsensusAggregator.LineConsensus(
                new BigDecimal(line),
                3,
                1,
                false,
                first,
                second,
                new BigDecimal("0.010000"),
                AsianMarketMath.fairPrice(first),
                AsianMarketMath.fairPrice(second),
                List.of("bet365", "pinnacle")
        );
    }

    private AsianOddsSnapshot row(
            String bookmaker,
            String handicapLine,
            String homeOdds,
            String awayOdds,
            String totalLine,
            Instant capturedAt,
            Long id
    ) {
        AsianOddsSnapshot snapshot = new AsianOddsSnapshot();
        snapshot.setId(id);
        snapshot.setMatchId(100L);
        snapshot.setProviderCode("novaapi");
        snapshot.setBookmakerCode(bookmaker);
        snapshot.setHandicapLine(new BigDecimal(handicapLine));
        snapshot.setHomeOdds(new BigDecimal(homeOdds));
        snapshot.setAwayOdds(new BigDecimal(awayOdds));
        snapshot.setTotalLine(new BigDecimal(totalLine));
        snapshot.setOverOdds(new BigDecimal("1.95"));
        snapshot.setUnderOdds(new BigDecimal("1.95"));
        snapshot.setCapturedAt(capturedAt);
        return snapshot;
    }
}
