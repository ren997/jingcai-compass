package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.util.StringUtils;

/**
 * 把 NovaAPI {@code /nova/tf/odds-history} 的时间序列在任意历史时点切成一条「截面快照」。
 *
 * <p>回测的关键不是重新实现模型，而是<b>把过去某一刻的盘口还原成一次真实同步写入后的形态</b>，
 * 再交给与线上完全相同的聚合 / 反解 / 选行 / 置信度逻辑。因此本类只做三件事：</p>
 *
 * <ol>
 *   <li><b>截面取价</b>：每个 option 取 {@code updateTime <= 决策时刻} 的最后一条变动
 *       （{@code points[]} 是<b>变动快照而非定时采样</b>，所以「最后一条」就是该时刻的精确价，不需要插值）；</li>
 *   <li><b>配对与保留档</b>：同档 Home/Away、Over/Under 必须两侧齐全；
 *       大小球保留档交给 {@link AsianTotalsLineSelector}，与实时同步同一套口径；</li>
 *   <li><b>展开交叉积</b>：只报让球不报大小球的庄也照样产行（大小球三列为 null），
 *       与 {@link NovaApiOddsPayloadMapper} 的落库形态逐行同构。</li>
 * </ol>
 *
 * <h2>两条必须守住的纪律</h2>
 *
 * <p><b>① 无泄漏（no lookahead）</b>：{@code updateTime > decisionAt} 的点一律丢弃，
 * 且不是「跳过继续找」而是「从结果里排除」——写成 {@code break} 会因为上游偶发乱序而漏掉更晚的点。
 * 得到的每个 {@code AsianOddsSnapshot.capturedAt} 与每个 {@code Quote.capturedAt} 都必然
 * {@code <= decisionAt}，这正是 {@code ck_backtest_cases_no_lookahead} 要钉住的不变式。</p>
 *
 * <p><b>② 时区按北京时间解释</b>：源侧 {@code updateTime} 不带时区，其值是<b>北京时间</b>
 * （实测两个 tf 接口皆然），而生产解析器 {@code NovaApiOddsPayloadMapper.parseTime} 按 UTC 解释——
 * 它只用于诊断排序所以无害，但回测照搬会让「赛前 105 分钟」变成「赛前 585 分钟」，且所有断言照样通过。
 * 本类只接受两种输入：带时区的 ISO-8601（归档重写后的规范形态，{@code Z} 结尾）与
 * 无时区的 {@code yyyy-MM-dd HH:mm:ss}（原始载荷，按 Asia/Shanghai 解释）。</p>
 *
 * <p><b>为什么不直接用 {@link AsianFreezeSnapshotSelector} 的本地复刻</b>：原型用 5 键排序少掉了
 * 「对齐大小球主盘」这第一键，那正是 2026-10-22 那批 13/13 全小球的成因。本类只负责造行，
 * 选行必须原样调用生产选择器。</p>
 */
public final class OddsHistorySnapshotBuilder {

    /** 切片口径版本号：改切片规则必须同步 bump，否则回测结论无法归因到代码版本。 */
    public static final String BUILDER_VERSION = "odds-history-slice-v1";

    /** 亚盘让球市场 ID。 */
    public static final int HANDICAP_MARKET_ID = 4;

    /** 大小球市场 ID。 */
    public static final int TOTALS_MARKET_ID = 5;

    private static final String HOME = "Home";
    private static final String AWAY = "Away";
    private static final String OVER = "Over";
    private static final String UNDER = "Under";

    /** 源侧无时区的 {@code updateTime} 是北京时间，不是 UTC。 */
    private static final ZoneId UPSTREAM_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter SPACE_SEPARATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 切出某个决策时点的截面快照。
     *
     * @param payload      odds-history 的 {@code data} 节点（也接受含 {@code data} 的外层信封）
     * @param decisionAt   决策时刻（瞬时）；所有报价只取该时刻之前的最后一条
     * @param matchId      内部比赛 ID，落库与决策都要用
     * @param providerCode 来源编码，写进每个伪行
     * @param payloadHash  归档的原始载荷 SHA-256，可空（用于把结论追回到具体一份原始序列）
     */
    public HistorySnapshot build(
            JsonNode payload,
            Instant decisionAt,
            Long matchId,
            String providerCode,
            String payloadHash
    ) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(decisionAt, "decisionAt must not be null");
        Objects.requireNonNull(matchId, "matchId must not be null（回测要求该场已在 matches 表内）");
        Objects.requireNonNull(providerCode, "providerCode must not be null");

        JsonNode data = payload.path("data").isObject() ? payload.path("data") : payload;
        Map<String, Ladders> byBookmaker = new TreeMap<>();
        int pointCount = 0;

        for (JsonNode provider : data.path("providers")) {
            String bookmaker = BookmakerCodes.of(text(provider, "name"));
            if (bookmaker.isBlank()) {
                continue;
            }
            Ladders ladders = byBookmaker.computeIfAbsent(bookmaker, key -> new Ladders());
            for (JsonNode market : provider.path("markets")) {
                int marketId = market.path("marketId").asInt(-1);
                Map<BigDecimal, SidePair> target;
                String firstSide;
                String secondSide;
                if (marketId == HANDICAP_MARKET_ID) {
                    target = ladders.handicap;
                    firstSide = HOME;
                    secondSide = AWAY;
                } else if (marketId == TOTALS_MARKET_ID) {
                    target = ladders.totals;
                    firstSide = OVER;
                    secondSide = UNDER;
                } else {
                    // 归档入库前已裁掉其它玩法；这里再挡一次，避免把无关市场的点计入 pointCount。
                    continue;
                }
                for (JsonNode option : market.path("options")) {
                    pointCount += option.path("points").size();
                    if (NovaApiOddsPayloadMapper.isSuspended(option)) {
                        // 与实时同步一致：不可交易的一侧不能当成报价写进快照，冻结行正是按快照报价计账的。
                        continue;
                    }
                    BigDecimal line = decimal(option, "line");
                    if (line == null) {
                        continue;
                    }
                    Sliced sliced = sliceAt(option.path("points"), decisionAt);
                    if (sliced == null || !AsianMarketMath.isUsablePrice(sliced.price())) {
                        continue;
                    }
                    SidePair pair = target.computeIfAbsent(
                            AsianMarketMath.normalizeLine(line), key -> new SidePair());
                    String side = text(option, "option");
                    if (firstSide.equalsIgnoreCase(side)) {
                        pair.first = sliced.price();
                        pair.firstAt = sliced.capturedAt();
                    } else if (secondSide.equalsIgnoreCase(side)) {
                        pair.second = sliced.price();
                        pair.secondAt = sliced.capturedAt();
                    }
                }
            }
        }

        Set<BigDecimal> keptTotalsLines = AsianTotalsLineSelector.select(
                byBookmaker.values().stream().map(Ladders::totalsQuotes).toList());

        List<AsianOddsSnapshot> rows = new ArrayList<>();
        // 伪行 id 必须稳定（选行器最后一键就是 id，且要能跨 run 复现），因此按
        // 庄家编码 → 让球线 → 大小球线 的字典序编号，而不是依赖载荷里的 provider 顺序。
        Map<String, Long> rowIdByPair = new HashMap<>();
        long nextRowId = 1L;
        for (Map.Entry<String, Ladders> entry : byBookmaker.entrySet()) {
            String bookmaker = entry.getKey();
            Ladders ladders = entry.getValue();
            List<BigDecimal> totalsLines = ladders.totals.entrySet().stream()
                    .filter(totals -> keptTotalsLines.contains(totals.getKey()))
                    .filter(totals -> totals.getValue().complete())
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            for (BigDecimal handicapLine : ladders.handicap.keySet().stream().sorted().toList()) {
                SidePair handicap = ladders.handicap.get(handicapLine);
                if (!handicap.complete()) {
                    continue;
                }
                if (totalsLines.isEmpty()) {
                    long rowId = nextRowId++;
                    rows.add(row(matchId, providerCode, bookmaker, rowId, handicapLine, handicap,
                            null, null, handicap.updatedAt(), payloadHash));
                    rowIdByPair.putIfAbsent(bookmaker + "|h|" + handicapLine, rowId);
                    continue;
                }
                for (BigDecimal totalsLine : totalsLines) {
                    SidePair totals = ladders.totals.get(totalsLine);
                    long rowId = nextRowId++;
                    rows.add(row(matchId, providerCode, bookmaker, rowId, handicapLine, handicap,
                            totalsLine, totals, laterOf(handicap.updatedAt(), totals.updatedAt()), payloadHash));
                    // 同一 (庄家, 大小球线) 会在多条让球线上重复出现，引用的快照行取第一次出现的那行。
                    rowIdByPair.putIfAbsent(bookmaker + "|t|" + totalsLine, rowId);
                    rowIdByPair.putIfAbsent(bookmaker + "|h|" + handicapLine, rowId);
                }
            }
        }

        List<AsianMarketConsensusAggregator.Quote> handicapQuotes = new ArrayList<>();
        List<AsianMarketConsensusAggregator.Quote> totalsQuotes = new ArrayList<>();
        for (Map.Entry<String, Ladders> entry : byBookmaker.entrySet()) {
            String bookmaker = entry.getKey();
            Ladders ladders = entry.getValue();
            ladders.handicap.forEach((line, pair) -> {
                if (!pair.complete()) {
                    return;
                }
                handicapQuotes.add(quote(providerCode, bookmaker, line, pair,
                        rowIdByPair.get(bookmaker + "|h|" + line)));
            });
            ladders.totals.forEach((line, pair) -> {
                if (!pair.complete()) {
                    return;
                }
                // 大小球共识要的是全阶梯（生产按「每家庄每档最新」取数），不受保留档裁剪影响；
                // 保留档只决定「写哪些交叉积行」，两者不是一回事。
                totalsQuotes.add(quote(providerCode, bookmaker, line, pair,
                        rowIdByPair.get(bookmaker + "|t|" + line)));
            });
        }

        Instant lastChangeAt = rows.stream()
                .map(AsianOddsSnapshot::getCapturedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return new HistorySnapshot(
                List.copyOf(rows),
                List.copyOf(handicapQuotes),
                List.copyOf(totalsQuotes),
                keptTotalsLines,
                byBookmaker.size(),
                pointCount,
                lastChangeAt
        );
    }

    private AsianOddsSnapshot row(
            Long matchId,
            String providerCode,
            String bookmaker,
            long rowId,
            BigDecimal handicapLine,
            SidePair handicap,
            BigDecimal totalLine,
            SidePair totals,
            Instant capturedAt,
            String payloadHash
    ) {
        AsianOddsSnapshot row = new AsianOddsSnapshot();
        row.setId(rowId);
        row.setMatchId(matchId);
        row.setProviderCode(providerCode);
        row.setBookmakerCode(bookmaker);
        row.setHandicapLine(handicapLine);
        row.setHomeOdds(handicap.first);
        row.setAwayOdds(handicap.second);
        if (totals != null) {
            row.setTotalLine(totalLine);
            row.setOverOdds(totals.first);
            row.setUnderOdds(totals.second);
        }
        row.setSnapshotType(OddsSnapshotTypeEnum.OTHER);
        row.setCapturedAt(capturedAt);
        row.setProviderUpdatedAt(capturedAt);
        row.setRawPayloadHash(payloadHash);
        return row;
    }

    private AsianMarketConsensusAggregator.Quote quote(
            String providerCode,
            String bookmaker,
            BigDecimal line,
            SidePair pair,
            Long snapshotId
    ) {
        return new AsianMarketConsensusAggregator.Quote(
                providerCode,
                bookmaker,
                line,
                pair.first,
                pair.second,
                pair.updatedAt(),
                snapshotId
        );
    }

    /**
     * 取 {@code updateTime <= decisionAt} 的最后一条变动。
     *
     * <p>不用 {@code break}：{@code points[]} 实测升序，但上游偶发乱序，提前跳出会读到更早的价格
     * ——那是<b>静默的时点错位</b>，不会报错，只会让回测用到未来信息或过期信息。</p>
     */
    private static Sliced sliceAt(JsonNode points, Instant decisionAt) {
        Sliced latest = null;
        for (JsonNode point : points) {
            if (!point.isObject()) {
                continue;
            }
            Instant at = parseInstant(text(point, "updateTime"));
            BigDecimal price = decimal(point, "odd");
            if (at == null || price == null || at.isAfter(decisionAt)) {
                continue;
            }
            if (latest == null || at.isAfter(latest.capturedAt())) {
                latest = new Sliced(price, at);
            }
        }
        return latest;
    }

    /**
     * 解析源侧时间文本。
     *
     * <p>带偏移（{@code Z} / {@code +08:00}）按自身偏移解释；无偏移视为<b>北京时间</b>。
     * 这条判定不能简化成「一律按 UTC」——见类注释的第二条纪律。</p>
     */
    private static Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            // 落到无时区分支
        }
        try {
            return LocalDateTime.parse(trimmed, SPACE_SEPARATED).atZone(UPSTREAM_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试 ISO 本地日期时间（带 T 无偏移）
        }
        try {
            return LocalDateTime.parse(trimmed).atZone(UPSTREAM_ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node, String fieldName) {
        JsonNode field = node == null ? null : node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        if (field.isNumber()) {
            return field.decimalValue();
        }
        String value = field.asText(null);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node == null ? null : node.get(fieldName);
        return field == null || field.isNull() ? null : field.asText(null);
    }

    private static Instant laterOf(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null || !right.isAfter(left)) {
            return left;
        }
        return right;
    }

    /** 某条 option 在决策时刻的价格与其最后变动时刻。 */
    private record Sliced(BigDecimal price, Instant capturedAt) {
    }

    /** 一家庄的两条阶梯（让球 / 大小球），值为该档两侧的截面报价。 */
    private static final class Ladders {
        private final Map<BigDecimal, SidePair> handicap = new LinkedHashMap<>();
        private final Map<BigDecimal, SidePair> totals = new LinkedHashMap<>();

        private Map<BigDecimal, AsianTotalsLineSelector.Quote> totalsQuotes() {
            Map<BigDecimal, AsianTotalsLineSelector.Quote> quotes = new LinkedHashMap<>();
            totals.forEach((line, pair) ->
                    quotes.put(line, new AsianTotalsLineSelector.Quote(pair.first, pair.second)));
            return quotes;
        }
    }

    /** 同一档的两侧报价与各自最后变动时刻。 */
    private static final class SidePair {
        private BigDecimal first;
        private BigDecimal second;
        private Instant firstAt;
        private Instant secondAt;

        private boolean complete() {
            return AsianMarketMath.isUsablePrice(first) && AsianMarketMath.isUsablePrice(second);
        }

        private Instant updatedAt() {
            return laterOf(firstAt, secondAt);
        }
    }

    /**
     * 一次切片的完整产物。
     *
     * <p>{@code rows} 是伪快照行（交叉积），{@code handicapQuotes} / {@code totalsQuotes} 是聚合输入；
     * 两组报价与 {@code rows} 的差别是<b>刻意的</b>：大小球共识要全阶梯，交叉积只写保留档。
     * 把它们压成一组会让「保留档」这一实现细节泄漏成模型输入。</p>
     *
     * @param bookmakerCount 载荷中出现过的庄家数（含在该时点尚无可用报价的庄）
     * @param pointCount     两个市场内的变快点总数（含决策时刻之后的点），用于衡量序列丰富度
     * @param lastChangeAt   该截面中最晚的报价变动时刻；空截面为 null
     */
    public record HistorySnapshot(
            List<AsianOddsSnapshot> rows,
            List<AsianMarketConsensusAggregator.Quote> handicapQuotes,
            List<AsianMarketConsensusAggregator.Quote> totalsQuotes,
            Set<BigDecimal> keptTotalsLines,
            int bookmakerCount,
            int pointCount,
            Instant lastChangeAt
    ) {

        /** 该时点连一行都没有：数据源尚未开出、已停盘，或决策时刻早于首个变快点。 */
        public boolean isEmpty() {
            return rows.isEmpty();
        }

        /** 该时点报出让球且两侧齐全的庄家数——与「载荷里有几家」不是一回事。 */
        public int handicapBookmakerCount() {
            return (int) handicapQuotes.stream()
                    .map(AsianMarketConsensusAggregator.Quote::bookmakerCode)
                    .distinct()
                    .count();
        }

        /** 该时点报出大小球且两侧齐全的庄家数。 */
        public int totalsBookmakerCount() {
            return (int) totalsQuotes.stream()
                    .map(AsianMarketConsensusAggregator.Quote::bookmakerCode)
                    .distinct()
                    .count();
        }

        /** 该时点的最晚报价变动时刻，用于与决策时刻做无泄漏断言。 */
        public Optional<Instant> latestQuoteAt() {
            return Optional.ofNullable(lastChangeAt);
        }
    }
}
