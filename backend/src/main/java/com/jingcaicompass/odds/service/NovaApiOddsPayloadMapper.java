package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * NovaAPI {@code /nova/tf/odds} 响应 → 亚盘快照写入行。
 *
 * <p><b>不能复用 {@link AsianOddsPayloadMapper} 的让球配对逻辑</b>：那边按
 * 「outcome.name 等于队名」配对，并要求 {@code homePoint + awayPoint == 0}；
 * NovaAPI 的 outcome 固定是 {@code Home}/{@code Away}，且同一档两侧的 {@code line}
 * <b>完全相同</b>而不是相反数。照搬会 100% 落进 {@code INCONSISTENT_SPREAD_PAIR}，
 * 表现为「解析成功但零条 line」。</p>
 *
 * <p>字段语义（真实抓包 + 官方文档核对）：</p>
 * <ul>
 *   <li>{@code marketId=4} 亚盘让球，options 用 {@code Home}/{@code Away}；
 *       {@code marketId=5} 大小球，options 用 {@code Over}/{@code Under}。</li>
 *   <li>{@code line} = <b>主队让球数</b>（负=主队让球），与
 *       {@link AsianOddsLineDto#handicapLine()} 的「主队让球为负」契约天然一致，不做取反。</li>
 *   <li>{@code line}/{@code odd} 是字符串形态文本，{@code +0} 这种带正号的取值真实存在，
 *       必须按文本读再转 {@link BigDecimal}。</li>
 *   <li>{@code suspended=true} 的一侧要丢弃：留着会把「不可交易」当成报价写进快照，
 *       而冻结行正是按快照报价计账的。</li>
 * </ul>
 *
 * <h2>为什么落库形态是交叉积</h2>
 *
 * <p>本表一行要同时承载让球与大小球两个市场，而冻结行要求<b>同一行</b>既命中共识让球线、
 * 又有齐全的大小球四字段（见 {@code AsianFreezeSnapshotSelector.freezable()}）。NovaAPI 的
 * 让球档与大小球档是两条独立的阶梯，只能展开成 (让球线 × 大小球线) 的交叉积，
 * 才能保证「任意让球线都有一行带大小球」。</p>
 *
 * <p>但全量交叉积会失控：实测一场平博 9 档让球 × 16 档大小球 = 144 行，1xBet 达 15×42=630 行，
 * 一场四家庄就是上千行，而同步是每 20 分钟一轮。因此大小球侧只保留<b>跨庄去水概率最平衡的
 * {@value #TOTALS_LINES_KEPT} 档</b>（判据见 {@link #mostBalancedTotalsLines}）：
 * 真实抓包下每场约 110~160 行，且这 K 档必然包含各庄自己的主盘线。
 * 若将来需要完整大小球阶梯（例如邻线回投扩档），把 K 调大即可，代价是写入量线性增长。</p>
 */
@Component
public class NovaApiOddsPayloadMapper {

    /** 与 {@code NovaSportteryProvider} 保持一致，供聚合与查询侧按 provider 隔离。 */
    public static final String PROVIDER_CODE = "NOVA_API";

    private static final int HANDICAP_MARKET_ID = 4;
    private static final int TOTALS_MARKET_ID = 5;

    /** 大小球侧保留的档位数（按跨庄去水概率的平衡度取前 K）。 */
    static final int TOTALS_LINES_KEPT = 4;

    private static final String HOME = "Home";
    private static final String AWAY = "Away";
    private static final String OVER = "Over";
    private static final String UNDER = "Under";

    /** 源侧时间不带时区，按 UTC 原样解释：该值仅用于诊断排序，不参与任何概率计算。 */
    private static final DateTimeFormatter UPDATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 中文庄家名 → 稳定 ASCII 编码。
     *
     * <p>低抽水白名单（{@code pinnacle,matchbook,betfair}）按编码匹配，中文名永远不命中并
     * 静默回退全量中位数——不是报错，是「看起来照常工作但置信度口径悄悄降级」。
     * 未登记的庄家回退为小写原文，保证不会误命中白名单。</p>
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

    /**
     * 解析聚合信封 {@code {provider, responses:[{matchId, status, body}]}}。
     *
     * @param root 聚合载荷根节点
     * @return 逐场盘口；单场解析失败落在 {@code parseError} 上，不中断其余场次
     */
    public List<AsianOddsMatchOddsDto> parse(JsonNode root) {
        JsonNode responses = root == null ? null : root.path("responses");
        if (responses == null || !responses.isArray()) {
            throw new BusinessException(
                    ErrorCode.DATA_SOURCE_PARSE_FAILED,
                    "NovaAPI 亚盘载荷缺少 responses 数组"
            );
        }
        List<AsianOddsMatchOddsDto> matches = new ArrayList<>(responses.size());
        for (JsonNode response : responses) {
            matches.add(parseSingle(response));
        }
        return List.copyOf(matches);
    }

    private AsianOddsMatchOddsDto parseSingle(JsonNode response) {
        Set<String> errors = new LinkedHashSet<>();
        String matchId = text(response, "matchId");
        if (!StringUtils.hasText(matchId)) {
            errors.add("MISSING_MATCH_ID");
            matchId = null;
        }

        JsonNode body = response.path("body");
        int code = body.path("code").asInt(-1);
        if (code != 200) {
            // 上游把「密钥过期」「无此比赛」都放在 code 里，不校验会把空 data 当成「这场没盘」。
            errors.add("UPSTREAM_CODE_" + code);
            return toMatchOdds(matchId, List.of(), errors);
        }
        JsonNode data = body.path("data");
        if (!data.isObject() || data.isNull()) {
            errors.add("MISSING_DATA");
            return toMatchOdds(matchId, List.of(), errors);
        }

        Map<String, BookmakerLines> byBookmaker = new LinkedHashMap<>();
        for (JsonNode provider : data.path("providers")) {
            String providerName = text(provider, "name");
            if (!StringUtils.hasText(providerName)) {
                errors.add("MISSING_PROVIDER_NAME");
                continue;
            }
            BookmakerLines lines = byBookmaker.computeIfAbsent(
                    bookmakerCode(providerName),
                    key -> new BookmakerLines()
            );
            for (JsonNode market : provider.path("markets")) {
                int marketId = market.path("marketId").asInt(-1);
                if (marketId == HANDICAP_MARKET_ID) {
                    collect(market, HOME, AWAY, lines.handicap);
                } else if (marketId == TOTALS_MARKET_ID) {
                    collect(market, OVER, UNDER, lines.totals);
                }
            }
        }

        Set<BigDecimal> keptTotalsLines = mostBalancedTotalsLines(byBookmaker.values());
        return toMatchOdds(matchId, crossProduct(byBookmaker, keptTotalsLines), errors);
    }

    /** 同一档的两侧报价；缺任一侧则该档不成立。 */
    private void collect(JsonNode market, String firstSide, String secondSide, Map<BigDecimal, SidePair> target) {
        for (JsonNode option : market.path("options")) {
            if (isSuspended(option)) {
                continue;
            }
            BigDecimal line = decimal(text(option, "line"));
            BigDecimal odd = decimal(text(option, "odd"));
            if (line == null || odd == null || odd.compareTo(BigDecimal.ONE) <= 0) {
                continue;
            }
            BigDecimal normalized = AsianMarketMath.normalizeLine(line);
            String side = text(option, "option");
            OffsetDateTime updatedAt = parseTime(text(option, "updateTime"));
            SidePair pair = target.computeIfAbsent(normalized, key -> new SidePair());
            if (firstSide.equalsIgnoreCase(side)) {
                pair.first = odd;
                pair.firstUpdatedAt = updatedAt;
            } else if (secondSide.equalsIgnoreCase(side)) {
                pair.second = odd;
                pair.secondUpdatedAt = updatedAt;
            }
        }
    }

    /**
     * 全庄最平衡的前 {@value #TOTALS_LINES_KEPT} 档大小球线。
     *
     * <p>判据必须用「去水概率离 50/50 的距离」，<b>不能按报出家数</b>。按家数排会系统性地选中
     * {@code 1.5 / 2.5 / 3.5 / 4.5} 这类整数与半球盘——它们档位稀疏、几乎每家都报，
     * 而真正的现代主盘是 {@code 2.75 / 3.0} 这种四分盘，只有少数庄报。
     * 按家数裁的后果是「聚合器把主盘定到 2.5」，看起来一切正常，但共识已经不是市场共识。</p>
     *
     * <p>排序：平衡度升序 → 家数降序 → 线值升序。家数作为次要键，是为了在两档同样平衡时
     * 优先保留能满足 {@code minBookmakers>=2} 的那一档。</p>
     */
    private Set<BigDecimal> mostBalancedTotalsLines(Collection<BookmakerLines> allBookmakers) {
        Map<BigDecimal, List<BigDecimal>> probabilities = new HashMap<>();
        Map<BigDecimal, Integer> reportCounts = new HashMap<>();
        for (BookmakerLines lines : allBookmakers) {
            lines.totals.forEach((line, pair) -> {
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
                        AsianMarketMath.median(entry.getValue())
                                .subtract(new BigDecimal("0.5"))
                                .abs()
                ))
                .sorted(Comparator
                        .comparing(Candidate::distance)
                        .thenComparing(Comparator.comparingInt(
                                (Candidate candidate) -> -reportCounts.getOrDefault(candidate.line(), 0)))
                        .thenComparing(Candidate::line))
                .limit(TOTALS_LINES_KEPT)
                .forEach(candidate -> kept.add(candidate.line()));
        return kept;
    }

    private List<AsianOddsLineDto> crossProduct(
            Map<String, BookmakerLines> byBookmaker,
            Set<BigDecimal> keptTotalsLines
    ) {
        List<AsianOddsLineDto> lines = new ArrayList<>();
        byBookmaker.forEach((bookmakerCode, bookmakerLines) -> {
            List<BigDecimal> totalsLines = bookmakerLines.totals.keySet().stream()
                    .filter(keptTotalsLines::contains)
                    .filter(line -> bookmakerLines.totals.get(line).isComplete())
                    .sorted()
                    .toList();
            for (Map.Entry<BigDecimal, SidePair> handicap : bookmakerLines.handicap.entrySet()) {
                SidePair handicapPair = handicap.getValue();
                if (!handicapPair.isComplete()) {
                    continue;
                }
                if (totalsLines.isEmpty()) {
                    // 只报让球不报大小球的庄：照样写入让球行，total 三字段留空。
                    lines.add(new AsianOddsLineDto(
                            bookmakerCode,
                            handicap.getKey(),
                            handicapPair.first,
                            handicapPair.second,
                            null,
                            null,
                            null,
                            handicapPair.updatedAt()
                    ));
                    continue;
                }
                for (BigDecimal totalsLine : totalsLines) {
                    SidePair totalsPair = bookmakerLines.totals.get(totalsLine);
                    lines.add(new AsianOddsLineDto(
                            bookmakerCode,
                            handicap.getKey(),
                            handicapPair.first,
                            handicapPair.second,
                            totalsLine,
                            totalsPair.first,
                            totalsPair.second,
                            laterOf(handicapPair.updatedAt(), totalsPair.updatedAt())
                    ));
                }
            }
        });
        return List.copyOf(lines);
    }

    private AsianOddsMatchOddsDto toMatchOdds(String matchId, List<AsianOddsLineDto> lines, Set<String> errors) {
        // 队名/开赛时间为空是有意的：单源下比赛由 external_match_id 直接解析，不再靠队名配对，
        // 而 /nova/tf/odds 响应本身也不含这两项。
        return new AsianOddsMatchOddsDto(
                matchId,
                null,
                null,
                null,
                false,
                lines,
                null,
                errors.isEmpty() ? null : String.join(",", errors)
        );
    }

    static String bookmakerCode(String chineseName) {
        String trimmed = chineseName.trim();
        return BOOKMAKER_CODES.getOrDefault(trimmed, trimmed.toLowerCase(Locale.ROOT));
    }

    private static boolean isSuspended(JsonNode option) {
        JsonNode suspended = option.path("suspended");
        if (suspended.isMissingNode() || suspended.isNull()) {
            return false;
        }
        if (suspended.isBoolean()) {
            return suspended.asBoolean();
        }
        String value = suspended.asText("").trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static BigDecimal decimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static OffsetDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), UPDATE_TIME_FORMAT).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static OffsetDateTime laterOf(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null || !right.isAfter(left)) {
            return left;
        }
        return right;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node == null ? null : node.get(fieldName);
        return field == null || field.isNull() ? null : field.asText(null);
    }

    /** 一家庄的两条盘口阶梯。 */
    private static final class BookmakerLines {
        private final Map<BigDecimal, SidePair> handicap = new LinkedHashMap<>();
        private final Map<BigDecimal, SidePair> totals = new LinkedHashMap<>();
    }

    /** 同一档的两侧报价与各自更新时间。 */
    private static final class SidePair {
        private BigDecimal first;
        private BigDecimal second;
        private OffsetDateTime firstUpdatedAt;
        private OffsetDateTime secondUpdatedAt;

        private boolean isComplete() {
            return first != null && second != null;
        }

        private OffsetDateTime updatedAt() {
            return laterOf(firstUpdatedAt, secondUpdatedAt);
        }
    }
}
