package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 把仓库内保存的 NovaAPI {@code /nova/tf/odds} 真实抓包转成聚合输入。
 *
 * <p>抓包来自 2026-10-21 的数据源验证（{@code tools/novaapi-probe}），每场含 4~6 家庄家、
 * 每家庄 2~21 档盘口，是校验「主盘选择是否命中平博」的唯一真实样本，避免消耗额度。</p>
 *
 * <p>结构约定：{@code data.providers[].markets[]}，{@code marketId=4} 为亚盘让球
 * （options 用 Home/Away），{@code marketId=5} 为大小球（options 用 Over/Under），
 * 同档盘口的主客两侧 {@code line} 文本完全相等。</p>
 */
final class NovaApiOddsFixture {

    static final String EVENT_2492881 = "asianodds/novaapi-2492881-tf-odds.json";
    static final String EVENT_2407136 = "asianodds/novaapi-2407136-tf-odds.json";
    static final String EVENT_2594567 = "asianodds/novaapi-2594567-tf-odds.json";

    static final String PROVIDER_CODE = "novaapi";

    private static final int HANDICAP_MARKET_ID = 4;
    private static final int TOTALS_MARKET_ID = 5;
    private static final DateTimeFormatter UPDATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * NovaAPI 的中文庄家名到稳定编码的映射。
     *
     * <p>白名单命中依赖该归一：入库时必须写 ASCII 编码，否则白名单永远不命中并静默回退全量。</p>
     */
    private static final Map<String, String> BOOKMAKER_CODES = Map.of(
            "平博", "pinnacle",
            "必发", "betfair",
            "威廉希尔", "williamhill",
            "Bet365", "bet365",
            "1xBet", "1xbet",
            "韦德", "betvictor",
            "利记", "liji"
    );

    private NovaApiOddsFixture() {
    }

    static Event load(String resourceName) {
        JsonNode root = readTree(resourceName);
        JsonNode data = root.path("data");
        List<AsianMarketConsensusAggregator.Quote> handicap = new ArrayList<>();
        List<AsianMarketConsensusAggregator.Quote> totals = new ArrayList<>();
        for (JsonNode provider : data.path("providers")) {
            String bookmakerCode = bookmakerCode(provider.path("name").asText());
            for (JsonNode market : provider.path("markets")) {
                int marketId = market.path("marketId").asInt();
                if (marketId == HANDICAP_MARKET_ID) {
                    handicap.addAll(quotes(market, bookmakerCode, "Home", "Away"));
                } else if (marketId == TOTALS_MARKET_ID) {
                    totals.addAll(quotes(market, bookmakerCode, "Over", "Under"));
                }
            }
        }
        return new Event(
                data.path("idEvent").asText(),
                data.hasNonNull("matchId") ? data.path("matchId").asLong() : null,
                List.copyOf(handicap),
                List.copyOf(totals)
        );
    }

    /**
     * 某家庄自身「最平衡」的盘口线，作为主盘选择的外部基准。
     *
     * <p>排序口径与聚合器一致（偏离 50/50 升序 → 线型偏好 → 线值），但只使用单家庄样本。</p>
     */
    static Optional<BigDecimal> mostBalancedLine(
            List<AsianMarketConsensusAggregator.Quote> quotes,
            String bookmakerCode
    ) {
        String normalizedCode = bookmakerCode.trim().toLowerCase(Locale.ROOT);
        Map<BigDecimal, BigDecimal> byLine = new LinkedHashMap<>();
        for (AsianMarketConsensusAggregator.Quote quote : quotes) {
            if (!normalizedCode.equals(quote.bookmakerCode().trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            byLine.put(
                    AsianMarketMath.normalizeLine(quote.line()),
                    AsianMarketMath.devigFirst(quote.firstPrice(), quote.secondPrice()));
        }
        return byLine.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<BigDecimal, BigDecimal> entry) ->
                                entry.getValue().subtract(new BigDecimal("0.5")).abs())
                        .thenComparingInt(entry -> AsianMarketMath.lineKindRank(entry.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    static String bookmakerCode(String chineseName) {
        return BOOKMAKER_CODES.getOrDefault(chineseName, chineseName.trim().toLowerCase(Locale.ROOT));
    }

    private static List<AsianMarketConsensusAggregator.Quote> quotes(
            JsonNode market,
            String bookmakerCode,
            String firstSide,
            String secondSide
    ) {
        Map<BigDecimal, SidePair> byLine = new LinkedHashMap<>();
        for (JsonNode option : market.path("options")) {
            String rawLine = option.path("line").asText("");
            if (rawLine.isBlank() || !option.hasNonNull("odd")) {
                continue;
            }
            BigDecimal line = AsianMarketMath.normalizeLine(new BigDecimal(rawLine.trim()));
            String side = option.path("option").asText();
            BigDecimal odd = new BigDecimal(option.path("odd").asText());
            Instant updatedAt = parseUpdateTime(option.path("updateTime").asText(""));
            SidePair pair = byLine.computeIfAbsent(line, key -> new SidePair());
            if (firstSide.equalsIgnoreCase(side)) {
                pair.first = odd;
                pair.firstUpdatedAt = updatedAt;
            } else if (secondSide.equalsIgnoreCase(side)) {
                pair.second = odd;
                pair.secondUpdatedAt = updatedAt;
            }
        }
        List<AsianMarketConsensusAggregator.Quote> quotes = new ArrayList<>();
        byLine.forEach((line, pair) -> {
            if (pair.first == null || pair.second == null) {
                return;
            }
            quotes.add(new AsianMarketConsensusAggregator.Quote(
                    PROVIDER_CODE,
                    bookmakerCode,
                    line,
                    pair.first,
                    pair.second,
                    laterOf(pair.firstUpdatedAt, pair.secondUpdatedAt),
                    null
            ));
        });
        return quotes;
    }

    private static Instant laterOf(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private static Instant parseUpdateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.trim(), UPDATE_TIME_FORMAT).toInstant(ZoneOffset.UTC);
    }

    private static JsonNode readTree(String resourceName) {
        try (InputStream stream = NovaApiOddsFixture.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("fixture not found on classpath: " + resourceName);
            }
            return MAPPER.readTree(stream);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read fixture: " + resourceName, exception);
        }
    }

    /** 一场抓包拆出的两个市场报价。 */
    record Event(
            String eventId,
            Long matchId,
            List<AsianMarketConsensusAggregator.Quote> handicap,
            List<AsianMarketConsensusAggregator.Quote> totals
    ) {

        int handicapBookmakerCount() {
            return bookmakerCount(handicap);
        }

        int totalsBookmakerCount() {
            return bookmakerCount(totals);
        }

        private int bookmakerCount(List<AsianMarketConsensusAggregator.Quote> quotes) {
            return (int) quotes.stream()
                    .map(AsianMarketConsensusAggregator.Quote::bookmakerCode)
                    .distinct()
                    .count();
        }
    }

    private static final class SidePair {
        private BigDecimal first;
        private BigDecimal second;
        private Instant firstUpdatedAt;
        private Instant secondUpdatedAt;
    }
}
