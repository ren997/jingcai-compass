package com.jingcaicompass.odds.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大小球保留档选择器的验收。
 *
 * <p>最关键的是 {@link #matchesLegacyImplementationOnRandomLadders()}：选择器是从
 * {@code NovaApiOddsPayloadMapper.mostBalancedTotalsLines} 里抽出来的，抽错一处不会报错，
 * 只会让<b>历史上已写入的快照</b>与<b>回测还原的截面</b>保留不同的档位。因此把旧实现原样
 * 抄在测试里当参照，用 20 组随机阶梯逐例比对。</p>
 */
class AsianTotalsLineSelectorTest {

    private static final BigDecimal FIFTY_PERCENT = new BigDecimal("0.5");

    @Test
    void returnsEmptyForNoLadders() {
        assertThat(AsianTotalsLineSelector.select(null)).isEmpty();
        assertThat(AsianTotalsLineSelector.select(List.of())).isEmpty();
        assertThat(AsianTotalsLineSelector.select(List.of(Map.of()))).isEmpty();
    }

    @Test
    void keepsAllLinesWhenLadderIsShorterThanLimit() {
        Set<BigDecimal> kept = AsianTotalsLineSelector.select(List.of(
                ladder(entry("2.50", "1.90", "1.90"))));

        assertThat(kept).containsExactly(new BigDecimal("2.50"));
    }

    @Test
    void dropsLinesWithUnusablePrices() {
        // 1.00 及以下无法去水：留着会让这条线参与中位数，把主盘拧向一条不可交易的档。
        Set<BigDecimal> kept = AsianTotalsLineSelector.select(List.of(
                ladder(entry("2.50", "1.00", "2.00"), entry("3.00", "1.85", "1.95"))));

        assertThat(kept).containsExactly(new BigDecimal("3.00"));
    }

    @Test
    void prefersBalancedQuarterLineOverSparseIntegerLine() {
        // 真实事故形态：整数盘 2.5 有 4 家庄、主盘 2.75 只有 2 家庄。
        // 按家数裁会选中 2.5，聚合器随后把主盘定到 2.5——共识已错却不报错。
        List<Map<BigDecimal, AsianTotalsLineSelector.Quote>> ladders = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            ladders.add(ladder(entry("2.50", "2.10", "1.75")));
        }
        ladders.add(ladder(entry("2.75", "1.93", "1.87")));
        ladders.add(ladder(entry("2.75", "1.91", "1.89")));

        Set<BigDecimal> kept = AsianTotalsLineSelector.select(ladders, 1);

        assertThat(kept).containsExactly(new BigDecimal("2.75"));
    }

    @Test
    void breaksTiesByReportCountThenLineValue() {
        // 两档平衡度完全相同（都是 1.90/2.00 的正反面 → 同样偏离 50/50），家数多者先。
        List<Map<BigDecimal, AsianTotalsLineSelector.Quote>> ladders = new ArrayList<>();
        ladders.add(ladder(entry("2.50", "1.90", "2.00")));
        ladders.add(ladder(entry("3.50", "1.90", "2.00")));
        ladders.add(ladder(entry("3.50", "1.90", "2.00")));

        assertThat(AsianTotalsLineSelector.select(ladders, 1))
                .as("同样平衡时优先能满足 minBookmakers 的那一档")
                .containsExactly(new BigDecimal("3.50"));

        List<Map<BigDecimal, AsianTotalsLineSelector.Quote>> oneEach = List.of(
                ladder(entry("2.50", "1.90", "2.00")),
                ladder(entry("3.50", "1.90", "2.00")));
        assertThat(AsianTotalsLineSelector.select(oneEach, 1))
                .as("家数也相同则线值升序，保证结果确定")
                .containsExactly(new BigDecimal("2.50"));
    }

    @Test
    void rejectsNonPositiveLineCount() {
        assertThatThrownBy(() -> AsianTotalsLineSelector.select(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("linesKept");
    }

    @Test
    void returnsUnmodifiableSet() {
        Set<BigDecimal> kept = AsianTotalsLineSelector.select(List.of(
                ladder(entry("2.50", "1.90", "2.00"))));

        assertThatThrownBy(() -> kept.add(new BigDecimal("9.99")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 与抽象前的旧实现逐例比对。
     *
     * <p>随机生成庄家数、档位数与价位，走 20 轮。旧实现从 mapper 原样复制在此，
     * 任何一处语义漂移（可用价过滤、家数次键方向、线值方向）都会在这里失败。</p>
     */
    @Test
    void matchesLegacyImplementationOnRandomLadders() {
        Random random = new Random(20261025L);
        for (int round = 0; round < 20; round++) {
            int bookmakerCount = 2 + random.nextInt(7);
            List<Map<BigDecimal, AsianTotalsLineSelector.Quote>> ladders = new ArrayList<>();
            List<Map<BigDecimal, LegacyPair>> legacyLadders = new ArrayList<>();
            for (int bookmaker = 0; bookmaker < bookmakerCount; bookmaker++) {
                Map<BigDecimal, AsianTotalsLineSelector.Quote> ladder = new LinkedHashMap<>();
                Map<BigDecimal, LegacyPair> legacyLadder = new LinkedHashMap<>();
                int lineCount = 1 + random.nextInt(6);
                for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
                    // 只在 0.25 网格上取值，与 normalizeLine 的口径一致。
                    BigDecimal line = new BigDecimal(15 + random.nextInt(20))
                            .divide(new BigDecimal("4"), 2, RoundingMode.HALF_UP);
                    BigDecimal first = new BigDecimal(150 + random.nextInt(70))
                            .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
                    BigDecimal second = new BigDecimal(150 + random.nextInt(70))
                            .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
                    ladder.put(line, new AsianTotalsLineSelector.Quote(first, second));
                    legacyLadder.put(line, new LegacyPair(first, second));
                }
                ladders.add(ladder);
                legacyLadders.add(legacyLadder);
            }

            assertThat(AsianTotalsLineSelector.select(ladders))
                    .as("round %s", round)
                    .isEqualTo(legacySelect(legacyLadders));
        }
    }

    // ---- 以下为照抄旧实现的参照版本，只用于差分测试，不参与生产 ----

    private static Set<BigDecimal> legacySelect(List<Map<BigDecimal, LegacyPair>> allBookmakers) {
        Map<BigDecimal, List<BigDecimal>> probabilities = new HashMap<>();
        Map<BigDecimal, Integer> reportCounts = new HashMap<>();
        for (Map<BigDecimal, LegacyPair> lines : allBookmakers) {
            lines.forEach((line, pair) -> {
                if (!pair.isComplete()) {
                    return;
                }
                probabilities.computeIfAbsent(line, key -> new ArrayList<>())
                        .add(AsianMarketMath.devigFirst(pair.first, pair.second));
                reportCounts.merge(line, 1, Integer::sum);
            });
        }

        record Candidate(BigDecimal line, BigDecimal distance) {
        }

        Set<BigDecimal> kept = new LinkedHashSet<>();
        probabilities.entrySet().stream()
                .map(entry -> new Candidate(
                        entry.getKey(),
                        AsianMarketMath.median(entry.getValue()).subtract(FIFTY_PERCENT).abs()))
                .sorted(Comparator
                        .comparing(Candidate::distance)
                        .thenComparing(Comparator.comparingInt(
                                (Candidate candidate) -> -reportCounts.getOrDefault(candidate.line(), 0)))
                        .thenComparing(Candidate::line))
                .limit(AsianTotalsLineSelector.DEFAULT_LINES_KEPT)
                .forEach(candidate -> kept.add(candidate.line()));
        return kept;
    }

    private record LegacyPair(BigDecimal first, BigDecimal second) {

        private boolean isComplete() {
            return first != null && second != null;
        }
    }

    // ---- 构造辅助 ----

    @SafeVarargs
    private static Map<BigDecimal, AsianTotalsLineSelector.Quote> ladder(Map.Entry<String, String>... entries) {
        Map<BigDecimal, AsianTotalsLineSelector.Quote> ladder = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String[] prices = entry.getValue().split("/");
            ladder.put(new BigDecimal(entry.getKey()),
                    new AsianTotalsLineSelector.Quote(new BigDecimal(prices[0]), new BigDecimal(prices[1])));
        }
        return ladder;
    }

    private static Map.Entry<String, String> entry(String line, String first, String second) {
        return Map.entry(line, first + "/" + second);
    }
}
