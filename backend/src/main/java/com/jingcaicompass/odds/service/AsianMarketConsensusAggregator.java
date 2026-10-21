package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 跨庄去水共识聚合，只做计算，不读写数据库。
 *
 * <p>顺序固定为「逐家去水 → 取中位 → 再归一」，不可颠倒：先取中位赔率再去水会被单个错价或
 * 延迟报价带偏，先逐家去水再取中位可以抵抗离群值。</p>
 *
 * <p>主盘定义是「使两边去水概率最接近 50/50 的那条线」，不是众数：实测出现过 7 家报 -1.5、
 * 2 家报 -1.25 而真盘是 -1.25 的反例。</p>
 */
public class AsianMarketConsensusAggregator {

    private static final BigDecimal FIFTY_PERCENT = new BigDecimal("0.5");
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final int minBookmakers;
    private final int minWhitelistBookmakers;
    private final Set<String> whitelist;

    public AsianMarketConsensusAggregator(AsianOddsAggregationProperties properties) {
        AsianOddsAggregationProperties effective =
                properties == null ? AsianOddsAggregationProperties.defaults() : properties;
        this.minBookmakers = effective.minBookmakers();
        this.minWhitelistBookmakers = effective.minWhitelistBookmakers();
        this.whitelist = effective.normalizedWhitelist();
    }

    /** 聚合一组同一市场的报价（同一市场 = 让球，或大小球）。 */
    public MarketAggregate aggregate(List<Quote> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return new MarketAggregate(List.of());
        }

        // 1) 清洗与去重：同一庄家在同一线只保留最新报价，避免多来源重复计数。
        Map<BookLineKey, Quote> deduped = new LinkedHashMap<>();
        for (Quote quote : quotes) {
            if (!usable(quote)) {
                continue;
            }
            BookLineKey key = new BookLineKey(
                    AsianMarketMath.normalizeLine(quote.line()), normalizeCode(quote.bookmakerCode()));
            Quote existing = deduped.get(key);
            if (existing == null || isMoreRecent(quote, existing)) {
                deduped.put(key, quote);
            }
        }
        if (deduped.isEmpty()) {
            return new MarketAggregate(List.of());
        }

        // 2) 按线分组后逐线聚合。
        Map<BigDecimal, List<Quote>> byLine = new LinkedHashMap<>();
        for (Map.Entry<BookLineKey, Quote> entry : deduped.entrySet()) {
            byLine.computeIfAbsent(entry.getKey().line(), key -> new ArrayList<>()).add(entry.getValue());
        }

        List<LineConsensus> consensuses = new ArrayList<>();
        for (Map.Entry<BigDecimal, List<Quote>> entry : byLine.entrySet()) {
            LineConsensus consensus = consensus(entry.getKey(), entry.getValue());
            if (consensus != null) {
                consensuses.add(consensus);
            }
        }

        // 3) 主盘排序：最平衡 → 线型偏好 → 样本家数 → 线值（确定性）。
        consensuses.sort(RANKING);
        return new MarketAggregate(List.copyOf(consensuses));
    }

    private LineConsensus consensus(BigDecimal line, List<Quote> quotes) {
        List<Quote> whitelisted = quotes.stream()
                .filter(quote -> whitelist.contains(normalizeCode(quote.bookmakerCode())))
                .toList();
        // 白名单按线判定：该线白名单家数达标才用白名单样本，否则该线回退全量。
        boolean whitelistApplied = whitelisted.size() >= minWhitelistBookmakers;
        List<Quote> sample = whitelistApplied ? whitelisted : quotes;
        if (sample.size() < minBookmakers) {
            return null;
        }

        List<BigDecimal> probabilities = new ArrayList<>(sample.size());
        for (Quote quote : sample) {
            probabilities.add(AsianMarketMath.devigFirst(quote.firstPrice(), quote.secondPrice()));
        }
        BigDecimal firstProbability = AsianMarketMath.median(probabilities);
        // 归一：两路边际概率互补时该步是恒等变换，保留是为了与设计文档顺序一致且防止后续扩展漏归一。
        BigDecimal secondProbability = ONE.subtract(firstProbability);
        BigDecimal total = firstProbability.add(secondProbability);
        firstProbability = firstProbability.divide(total, AsianMarketMath.PROBABILITY_SCALE, RoundingMode.HALF_UP);
        secondProbability = secondProbability.divide(total, AsianMarketMath.PROBABILITY_SCALE, RoundingMode.HALF_UP);

        List<String> bookmakers = sample.stream()
                .map(Quote::bookmakerCode)
                .filter(Objects::nonNull)
                .map(String::trim)
                .sorted()
                .toList();

        return new LineConsensus(
                line,
                sample.size(),
                whitelisted.size(),
                whitelistApplied,
                firstProbability,
                secondProbability,
                AsianMarketMath.populationStandardDeviation(probabilities),
                AsianMarketMath.fairPrice(firstProbability),
                AsianMarketMath.fairPrice(secondProbability),
                bookmakers
        );
    }

    private boolean usable(Quote quote) {
        return quote != null
                && quote.line() != null
                && quote.bookmakerCode() != null
                && !quote.bookmakerCode().isBlank()
                && AsianMarketMath.isUsablePrice(quote.firstPrice())
                && AsianMarketMath.isUsablePrice(quote.secondPrice());
    }

    private boolean isMoreRecent(Quote candidate, Quote existing) {
        if (candidate.capturedAt() != null && existing.capturedAt() != null
                && !candidate.capturedAt().equals(existing.capturedAt())) {
            return candidate.capturedAt().isAfter(existing.capturedAt());
        }
        if (candidate.capturedAt() != null && existing.capturedAt() == null) {
            return true;
        }
        if (candidate.capturedAt() == null && existing.capturedAt() != null) {
            return false;
        }
        return candidate.snapshotId() != null
                && (existing.snapshotId() == null || candidate.snapshotId() > existing.snapshotId());
    }

    private String normalizeCode(String bookmakerCode) {
        return bookmakerCode == null ? "" : bookmakerCode.trim().toLowerCase(Locale.ROOT);
    }

    /** 单家庄家单条盘口线的报价。 */
    public record Quote(
            String providerCode,
            String bookmakerCode,
            BigDecimal line,
            BigDecimal firstPrice,
            BigDecimal secondPrice,
            Instant capturedAt,
            Long snapshotId
    ) {
    }

    /** 某条盘口线的共识结果。 */
    public record LineConsensus(
            BigDecimal line,
            int sampleCount,
            int whitelistCount,
            boolean whitelistApplied,
            BigDecimal firstProbability,
            BigDecimal secondProbability,
            BigDecimal dispersion,
            BigDecimal fairFirstPrice,
            BigDecimal fairSecondPrice,
            List<String> bookmakers
    ) {

        /** 是否偏向首边（主队 / 大球）；恰好 0.5 时视为未偏向。 */
        public boolean favorsFirstSide() {
            return firstProbability.compareTo(FIFTY_PERCENT) > 0;
        }

        /** 两边去水概率的偏离度，越小越接近「标准盘」。 */
        public BigDecimal imbalance() {
            return firstProbability.subtract(FIFTY_PERCENT).abs();
        }

        /** 样本来源说明，用于摘要与日志。 */
        public String sampleSource() {
            return whitelistApplied
                    ? "白名单 " + sampleCount + " 家（白名单共 " + whitelistCount + " 家）"
                    : "回退全量 " + sampleCount + " 家（白名单仅 " + whitelistCount + " 家）";
        }
    }

    /** 一个市场全部合格盘口线，按主盘优先排序。 */
    public record MarketAggregate(List<LineConsensus> ranked) {

        /** 主盘：最平衡且样本达标的那条线。 */
        public Optional<LineConsensus> best() {
            return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
        }

        /** 取指定线的共识，用于判定冻结行自身盘口线上的方向。 */
        public Optional<LineConsensus> atLine(BigDecimal line) {
            if (line == null) {
                return Optional.empty();
            }
            BigDecimal normalized = AsianMarketMath.normalizeLine(line);
            return ranked.stream()
                    .filter(consensus -> consensus.line().compareTo(normalized) == 0)
                    .findFirst();
        }

        public boolean isEmpty() {
            return ranked.isEmpty();
        }
    }

    private record BookLineKey(BigDecimal line, String bookmakerCode) {
    }

    private static final Comparator<LineConsensus> RANKING = Comparator
            .comparing(LineConsensus::imbalance)
            .thenComparingInt(consensus -> AsianMarketMath.lineKindRank(consensus.line()))
            .thenComparing(Comparator.comparingInt(LineConsensus::sampleCount).reversed())
            .thenComparing(LineConsensus::line);
}
