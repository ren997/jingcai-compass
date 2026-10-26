package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

/**
 * 归档前把 odds-history 的响应裁剪到「让球 + 大小球」两个市场。
 *
 * <p><b>为什么必须裁</b>：完整响应实测 388~432KB/场，4 个月约 2400 场接近 1GB；
 * 而切片只需要 {@code marketId} 4（让球）与 5（大小球）。体彩玩法在
 * {@code sporttery_pool_snapshots} 另有来源，重复留一份只会让「哪个是权威」变成疑问。</p>
 *
 * <p><b>裁掉的必须记账</b>：{@link Trimmed#trimmed()} 为真表示确实丢过东西。
 * 将来若从别处灌入未裁剪载荷，切片器不会去假设「载荷里一定有胜平负」——这个差异要可解释。</p>
 *
 * <p>裁剪同时产出归档表需要的四个统计量（庄家数、点数、序列首末点）。
 * 它们不是顺手算的装饰：{@code ck_odds_history_archive_payload_scope} 用它们区分
 * 「真的采到了序列」与「看着有数据其实没解析」——{@code point_count == 0} 的 SUCCESS 行是
 * 最典型的一种静默失败。</p>
 */
public final class OddsHistoryPayloadTrimmer {

    /** 裁剪规则版本号：改裁剪口径必须 bump，否则归档的 payload_hash 无法与规则版本对应。 */
    public static final String TRIMMER_VERSION = "odds-history-trim-v1";

    private static final String PROVIDERS = "providers";
    private static final String MARKETS = "markets";
    private static final String OPTIONS = "options";
    private static final String POINTS = "points";
    private static final String DATA = "data";

    /**
     * 裁剪。
     *
     * @param body odds-history 的完整响应体（含 {@code code}/{@code msg}/{@code data}）
     * @return 裁剪后的载荷与统计量；{@code body} 不是对象时返回空结果
     */
    public Trimmed trim(JsonNode body) {
        Objects.requireNonNull(body, "body must not be null");
        if (!body.isObject()) {
            return Trimmed.empty();
        }

        boolean hasEnvelope = body.path(DATA).isObject();
        JsonNode dataNode = hasEnvelope ? body.path(DATA) : body;

        int originalProviders = dataNode.path(PROVIDERS).size();
        int originalMarkets = 0;
        ArrayNode keptProviders = JsonNodeFactory.instance.arrayNode();
        int bookmakerCount = 0;
        int pointCount = 0;
        Instant seriesStart = null;
        Instant seriesEnd = null;

        for (JsonNode provider : dataNode.path(PROVIDERS)) {
            originalMarkets += provider.path(MARKETS).size();
            ArrayNode keptMarkets = JsonNodeFactory.instance.arrayNode();
            for (JsonNode market : provider.path(MARKETS)) {
                int marketId = market.path("marketId").asInt(-1);
                if (marketId != OddsHistorySnapshotBuilder.HANDICAP_MARKET_ID
                        && marketId != OddsHistorySnapshotBuilder.TOTALS_MARKET_ID) {
                    continue;
                }
                if (market.path(OPTIONS).isEmpty()) {
                    // 有市场但没有任何 option：留着只会让「庄家报了这个市场」变成一句空话。
                    continue;
                }
                for (JsonNode option : market.path(OPTIONS)) {
                    for (JsonNode point : option.path(POINTS)) {
                        pointCount++;
                        Instant at = OddsHistorySnapshotBuilder.parseUpstreamInstant(
                                text(point, "updateTime"));
                        if (at == null) {
                            continue;
                        }
                        if (seriesStart == null || at.isBefore(seriesStart)) {
                            seriesStart = at;
                        }
                        if (seriesEnd == null || at.isAfter(seriesEnd)) {
                            seriesEnd = at;
                        }
                    }
                }
                keptMarkets.add(market.deepCopy());
            }
            if (keptMarkets.isEmpty()) {
                continue;
            }
            bookmakerCount++;
            ObjectNode keptProvider = provider.deepCopy();
            // 只改 providers[].markets，其余字段（name / id 等）原样保留：庄家编码要靠 name 映射。
            keptProvider.set(MARKETS, keptMarkets);
            keptProviders.add(keptProvider);
        }

        ObjectNode trimmedData = dataNode.deepCopy();
        trimmedData.set(PROVIDERS, keptProviders);

        JsonNode result;
        if (hasEnvelope) {
            ObjectNode root = body.deepCopy();
            root.set(DATA, trimmedData);
            result = root;
        } else {
            result = trimmedData;
        }

        int keptMarkets = 0;
        for (JsonNode provider : keptProviders) {
            keptMarkets += provider.path(MARKETS).size();
        }
        boolean trimmed = originalProviders != bookmakerCount || originalMarkets != keptMarkets;
        return new Trimmed(result, bookmakerCount, pointCount, seriesStart, seriesEnd, trimmed);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node == null ? null : node.get(fieldName);
        return field == null || field.isNull() ? null : field.asText(null);
    }

    /**
     * 裁剪结果。
     *
     * @param payload       裁剪后的载荷（保持上游原有形状：有 {@code data} 信封就仍然有）
     * @param bookmakerCount 保留后仍有让球或大小球 option 的庄家数
     * @param pointCount    两个市场内的变快点总数（含时间无法解析的点）
     * @param seriesStartAt 序列首点时刻；一个点都没有时为 null
     * @param seriesEndAt   序列末点时刻；一个点都没有时为 null
     * @param trimmed       是否确实丢弃了内容（庄家或市场数量变少）
     */
    public record Trimmed(
            JsonNode payload,
            int bookmakerCount,
            int pointCount,
            Instant seriesStartAt,
            Instant seriesEndAt,
            boolean trimmed
    ) {

        static Trimmed empty() {
            return new Trimmed(null, 0, 0, null, null, false);
        }

        /** 有可用序列：既有变快点，又能定出首末点时刻。 */
        public boolean hasSeries() {
            return payload != null && bookmakerCount > 0 && pointCount > 0
                    && seriesStartAt != null && seriesEndAt != null;
        }

        /** 空但合法的载荷（例如 {@code providers: []}）：上游有回应，但没有任何可用序列。 */
        public boolean isEmptyPayload() {
            return payload == null || bookmakerCount == 0 || pointCount == 0;
        }
    }
}
