package com.jingcaicompass.odds.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨庄挑选「最平衡」的若干档大小球线，作为交叉积落库时要保留的档位。
 *
 * <p>落库是 (让球线 × 大小球线) 的交叉积，全量展开会失控：实测一场平博 9 档让球 × 16 档大小球
 * = 144 行，1xBet 达 15×42=630 行，而同步每 20 分钟一轮。因此大小球侧只保留最平衡的前
 * {@value #DEFAULT_LINES_KEPT} 档，代价是写入量线性增长而不是乘积增长。</p>
 *
 * <p><b>判据必须用「去水概率离 50/50 的距离」，不能按报出家数。</b>按家数排会系统性选中
 * {@code 1.5 / 2.5 / 3.5 / 4.5} 这类整数与半球盘——它们档位稀疏、几乎每家都报，而真正的现代主盘
 * 是 {@code 2.75 / 3.0} 这种四分盘，只有少数庄报。按家数裁的后果是「聚合器把主盘定到 2.5」，
 * 看起来一切正常，但共识已经不是市场共识。</p>
 *
 * <p>排序：平衡度升序 → 家数降序 → 线值升序。家数作为次要键，是为了在两档同样平衡时优先保留
 * 能满足 {@code minBookmakers>=2} 的那一档。</p>
 *
 * <h2>为什么抽成独立类</h2>
 *
 * <p>同一套口径有两个使用者：实时同步的 {@link NovaApiOddsPayloadMapper}（决定<b>写哪些行</b>）与
 * 历史回测的 {@link OddsHistorySnapshotBuilder}（决定<b>还原哪些行</b>）。两处若各写一份，回测与
 * 实盘就会在「保留哪 4 档」上静默分叉，而分叉不会报错，只会让回测结论不可外推到线上。</p>
 */
public final class AsianTotalsLineSelector {

    /** 大小球侧保留的档位数（按跨庄去水概率的平衡度取前 K）。 */
    public static final int DEFAULT_LINES_KEPT = 4;

    private static final BigDecimal FIFTY_PERCENT = new BigDecimal("0.5");

    private AsianTotalsLineSelector() {
    }

    /**
     * 用生产口径的 {@value #DEFAULT_LINES_KEPT} 档选取。
     *
     * @param bookmakerLadders 每家庄一条「大小球线 → 两侧报价」阶梯；只报让球不报大小球的庄传空表
     */
    public static Set<BigDecimal> select(Collection<? extends Map<BigDecimal, Quote>> bookmakerLadders) {
        return select(bookmakerLadders, DEFAULT_LINES_KEPT);
    }

    /**
     * 按指定档数选取，供回测参数扫描与单测使用。
     *
     * @param linesKept 保留档数，必须为正；调用方传 {@link #DEFAULT_LINES_KEPT} 即与生产一致
     * @return 保留线集合；无任何可用报价时返回空集（不抛异常——「这场还没开大小球」是正常状态）
     */
    public static Set<BigDecimal> select(
            Collection<? extends Map<BigDecimal, Quote>> bookmakerLadders,
            int linesKept
    ) {
        if (linesKept < 1) {
            throw new IllegalArgumentException("linesKept must be positive, was " + linesKept);
        }
        Set<BigDecimal> kept = new LinkedHashSet<>();
        if (bookmakerLadders == null || bookmakerLadders.isEmpty()) {
            return Collections.unmodifiableSet(kept);
        }

        Map<BigDecimal, List<BigDecimal>> probabilities = new HashMap<>();
        Map<BigDecimal, Integer> reportCounts = new HashMap<>();
        for (Map<BigDecimal, Quote> ladder : bookmakerLadders) {
            if (ladder == null) {
                continue;
            }
            ladder.forEach((line, quote) -> {
                if (line == null || quote == null || !quote.complete()) {
                    return;
                }
                probabilities.computeIfAbsent(line, key -> new ArrayList<>())
                        .add(AsianMarketMath.devigFirst(quote.firstPrice(), quote.secondPrice()));
                reportCounts.merge(line, 1, Integer::sum);
            });
        }

        record Candidate(BigDecimal line, BigDecimal distance) {
        }

        probabilities.entrySet().stream()
                .map(entry -> new Candidate(
                        entry.getKey(),
                        AsianMarketMath.median(entry.getValue())
                                .subtract(FIFTY_PERCENT)
                                .abs()
                ))
                .sorted(Comparator
                        .comparing(Candidate::distance)
                        .thenComparing(Comparator.comparingInt(
                                (Candidate candidate) -> -reportCounts.getOrDefault(candidate.line(), 0)))
                        .thenComparing(Candidate::line))
                .limit(linesKept)
                .forEach(candidate -> kept.add(candidate.line()));
        return Collections.unmodifiableSet(kept);
    }

    /**
     * 同一档大小球的两侧报价。
     *
     * <p>{@link #complete()} 顺带校验价位可用：{@code 1.00} 及以下会让去水得到无意义概率
     * （{@link AsianMarketMath#isUsablePrice} 的契约），保留这种档位等于让整条线参与中位数污染。</p>
     */
    public record Quote(BigDecimal firstPrice, BigDecimal secondPrice) {

        public boolean complete() {
            return AsianMarketMath.isUsablePrice(firstPrice) && AsianMarketMath.isUsablePrice(secondPrice);
        }
    }
}
