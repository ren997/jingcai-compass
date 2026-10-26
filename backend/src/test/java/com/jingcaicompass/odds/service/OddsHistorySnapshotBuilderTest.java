package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator.Quote;
import com.jingcaicompass.odds.service.OddsHistorySnapshotBuilder.HistorySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史赔率截面切片器的验收。
 *
 * <p>三组断言各自守住一类「不报错但结论不可信」的缺陷：</p>
 * <ul>
 *   <li>时点语义：T 之后的点必须不可见，且无时区的 {@code updateTime} 必须按<b>北京时间</b>解释
 *       （按 UTC 解释会让赛前 105 分钟变成赛前 585 分钟，所有断言照样通过）；</li>
 *   <li>形态同构：产出的伪行必须与实时同步写入的交叉积逐列同构，否则生产选择器会在回测里选到别的行；</li>
 *   <li>确定性：同一份载荷重复切片必须产出相同的伪行 id——选行器最后一键就是 id。</li>
 * </ul>
 */
class OddsHistorySnapshotBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant KICKOFF = Instant.parse("2026-10-23T09:00:00Z");
    /** 原型已校验的截面：开赛前 105 分钟。 */
    private static final Instant DECISION_AT = Instant.parse("2026-10-23T07:15:00Z");
    private static final Long MATCH_ID = 41L;
    private static final String PROVIDER = "NOVA_API";
    private static final String HASH = "0".repeat(64);

    private final OddsHistorySnapshotBuilder builder = new OddsHistorySnapshotBuilder();

    @Test
    void returnsEmptySnapshotBeforeAnyPoint() {
        HistorySnapshot snapshot = build(basePayload(), Instant.parse("2026-10-23T05:00:00Z"));

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(snapshot.rows()).isEmpty();
        assertThat(snapshot.handicapQuotes()).isEmpty();
        assertThat(snapshot.totalsQuotes()).isEmpty();
        assertThat(snapshot.keptTotalsLines()).isEmpty();
        assertThat(snapshot.latestQuoteAt()).isEmpty();
        // 庄家仍算出现过：它报了价，只是当时还没开出任何变快点。
        assertThat(snapshot.bookmakerCount()).isEqualTo(2);
        assertThat(snapshot.pointCount()).isPositive();
    }

    @Test
    void ignoresPointsAfterDecisionAt() {
        ObjectNode payload = doc();
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode handicap = market(pinnacle, 4);
        option(handicap, "Home", "0", "2026-10-23 14:00:00|1.90", "2026-10-23 16:00:00|1.95");
        option(handicap, "Away", "0", "2026-10-23 14:00:00|2.00", "2026-10-23 16:00:00|1.95");

        // 15:15 北京时间 = 07:15Z：14:00 的点可见、16:00 的点不可见。
        HistorySnapshot snapshot = build(payload, DECISION_AT);

        assertThat(snapshot.handicapQuotes()).hasSize(1);
        Quote quote = snapshot.handicapQuotes().get(0);
        assertThat(quote.firstPrice()).isEqualByComparingTo("1.90");
        assertThat(quote.secondPrice()).isEqualByComparingTo("2.00");
        assertThat(quote.capturedAt()).isEqualTo(Instant.parse("2026-10-23T06:00:00Z"));
    }

    @Test
    void includesPointExactlyAtDecisionAt() {
        ObjectNode payload = doc();
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode handicap = market(pinnacle, 4);
        // 15:14:21 北京时间 = 07:14:21Z，恰在决策时刻之前一分多钟：必须可见。
        option(handicap, "Home", "0", "2026-10-23 15:14:21|1.88");
        option(handicap, "Away", "0", "2026-10-23 15:14:21|2.02");

        HistorySnapshot snapshot = build(payload, Instant.parse("2026-10-23T07:14:21Z"));

        assertThat(snapshot.handicapQuotes()).hasSize(1);
        assertThat(snapshot.handicapQuotes().get(0).capturedAt())
                .isEqualTo(Instant.parse("2026-10-23T07:14:21Z"));
    }

    @Test
    void interpretsBareUpdateTimeAsShanghai() {
        ObjectNode payload = doc();
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode handicap = market(pinnacle, 4);
        option(handicap, "Home", "0", "2026-10-23 15:14:21|1.88");
        option(handicap, "Away", "0", "2026-10-23 15:14:21|2.02");

        assertThat(build(payload, Instant.parse("2026-10-23T07:14:20Z")).isEmpty())
                .as("按北京时间解释：15:14:21 对应 07:14:21Z，早 1 秒的截面看不到它")
                .isTrue();
        assertThat(build(payload, Instant.parse("2026-10-23T07:14:21Z")).isEmpty())
                .as("若误按 UTC 解释，这一刻会被判成赛前 8 小时之前的数据并静默通过")
                .isFalse();
    }

    @Test
    void treatsOffsetAndInstantTextsAsTheSameMoment() {
        ObjectNode withOffset = doc();
        ObjectNode offsetHandicap = market(provider(withOffset, "平博"), 4);
        option(offsetHandicap, "Home", "0", "2026-10-23T15:14:21+08:00|1.88");
        option(offsetHandicap, "Away", "0", "2026-10-23T15:14:21+08:00|2.02");

        ObjectNode withZulu = doc();
        ObjectNode zuluHandicap = market(provider(withZulu, "平博"), 4);
        option(zuluHandicap, "Home", "0", "2026-10-23T07:14:21Z|1.88");
        option(zuluHandicap, "Away", "0", "2026-10-23T07:14:21Z|2.02");

        assertThat(build(withOffset, DECISION_AT).handicapQuotes())
                .isEqualTo(build(withZulu, DECISION_AT).handicapQuotes());
    }

    @Test
    void capturesLatestOfFourSidesAsRowCapturedAt() {
        ObjectNode payload = doc();
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode handicap = market(pinnacle, 4);
        option(handicap, "Home", "-0.5", "2026-10-23 14:00:00|1.90");
        option(handicap, "Away", "-0.5", "2026-10-23 14:10:00|2.00");
        ObjectNode totals = market(pinnacle, 5);
        option(totals, "Over", "2.5", "2026-10-23 14:20:00|1.85");
        option(totals, "Under", "2.5", "2026-10-23 14:30:00|1.95");

        HistorySnapshot snapshot = build(payload, DECISION_AT);

        assertThat(snapshot.rows()).hasSize(1);
        AsianOddsSnapshot row = snapshot.rows().get(0);
        // 四个水位里最晚的是小球 14:30 → 06:30Z。写成 handicap 侧的时间会让 no-lookahead 断言失真。
        assertThat(row.getCapturedAt()).isEqualTo(Instant.parse("2026-10-23T06:30:00Z"));
        assertThat(row.getProviderUpdatedAt()).isEqualTo(row.getCapturedAt());
        assertThat(row.getSnapshotType()).isEqualTo(OddsSnapshotTypeEnum.OTHER);
        assertThat(row.getRawPayloadHash()).isEqualTo(HASH);
        assertThat(row.getMatchId()).isEqualTo(MATCH_ID);
        assertThat(row.getProviderCode()).isEqualTo(PROVIDER);
        assertThat(row.getBookmakerCode()).isEqualTo("pinnacle");
        assertThat(row.getHandicapLine()).isEqualByComparingTo("-0.5");
        assertThat(row.getTotalLine()).isEqualByComparingTo("2.5");
        // 让球报价的 capturedAt 是它自己那一对的时间，不是行时间。
        assertThat(snapshot.handicapQuotes().get(0).capturedAt())
                .isEqualTo(Instant.parse("2026-10-23T06:10:00Z"));
        assertThat(snapshot.totalsQuotes().get(0).capturedAt())
                .isEqualTo(Instant.parse("2026-10-23T06:30:00Z"));
    }

    @Test
    void expandsCrossProductOverKeptTotalsLines() {
        HistorySnapshot snapshot = build(basePayload(), DECISION_AT);

        // 保留档由选择器决定：2.75 最平衡，2.5/3.0 次之，3.25 第四；3.5 被裁掉。
        assertThat(snapshot.keptTotalsLines())
                .containsExactlyInAnyOrder(
                        new BigDecimal("2.50"), new BigDecimal("2.75"),
                        new BigDecimal("3.00"), new BigDecimal("3.25"));
        assertThat(snapshot.rows())
                .as("平博 2 档让球 × 4 档保留大小球 + Bet365 1 档让球 × 4 档")
                .hasSize(12);
        assertThat(snapshot.rows())
                .filteredOn(row -> "pinnacle".equals(row.getBookmakerCode()))
                .hasSize(8);
        assertThat(snapshot.rows())
                .allSatisfy(row -> {
                    assertThat(row.getTotalLine()).isNotNull();
                    assertThat(row.getOverOdds()).isNotNull();
                    assertThat(row.getUnderOdds()).isNotNull();
                });
        assertThat(snapshot.totalsQuotes())
                .as("大小球共识要全阶梯，不受保留档裁剪：两家各 5 档")
                .hasSize(10);
    }

    @Test
    void emitsHandicapOnlyRowsWhenBookmakerReportsNoTotals() {
        ObjectNode payload = doc();
        ObjectNode betfair = provider(payload, "必发");
        ObjectNode handicap = market(betfair, 4);
        option(handicap, "Home", "-0.25", "2026-10-23 14:00:00|1.92");
        option(handicap, "Away", "-0.25", "2026-10-23 14:00:00|1.98");

        HistorySnapshot snapshot = build(payload, DECISION_AT);

        assertThat(snapshot.rows()).hasSize(1);
        AsianOddsSnapshot row = snapshot.rows().get(0);
        assertThat(row.getBookmakerCode()).isEqualTo("betfair");
        assertThat(row.getTotalLine()).isNull();
        assertThat(row.getOverOdds()).isNull();
        assertThat(row.getUnderOdds()).isNull();
        assertThat(snapshot.totalsQuotes()).isEmpty();
        assertThat(snapshot.keptTotalsLines()).isEmpty();
        // 行仍在：只报让球的庄照样参与让球共识，与实时落库的 crossProduct 分支一致。
        assertThat(snapshot.handicapQuotes()).hasSize(1);
    }

    @Test
    void dropsSuspendedSide() {
        ObjectNode payload = doc();
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode handicap = market(pinnacle, 4);
        option(handicap, "Home", "0", "2026-10-23 14:00:00|1.90");
        ObjectNode away = option(handicap, "Away", "0", "2026-10-23 14:00:00|2.00");
        away.put("suspended", true);

        HistorySnapshot snapshot = build(payload, DECISION_AT);

        assertThat(snapshot.isEmpty())
                .as("一侧暂停等于该档不可交易，不能拿暂停前的水位当报价")
                .isTrue();
        assertThat(snapshot.handicapQuotes()).isEmpty();
    }

    @Test
    void dropsPairsMissingOneSide() {
        ObjectNode payload = doc();
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode totals = market(pinnacle, 5);
        option(totals, "Over", "2.5", "2026-10-23 14:00:00|1.85");

        assertThat(build(payload, DECISION_AT).isEmpty()).isTrue();
    }

    @Test
    void ignoresMarketsOtherThanHandicapAndTotals() {
        ObjectNode payload = doc();
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode other = market(pinnacle, 1);
        option(other, "Home", "0", "2026-10-23 14:00:00|1.50", "2026-10-23 14:30:00|1.55");
        HistorySnapshot withoutQuotes = build(payload, DECISION_AT);

        ObjectNode withHandicap = basePayload();
        int basePointCount = build(withHandicap, DECISION_AT).pointCount();

        assertThat(withoutQuotes.pointCount())
                .as("其它玩法的点不能计入 pointCount，否则序列丰富度会虚高")
                .isZero();
        // 平博 4 个让球 option + 10 个大小球 option，Bet365 2 + 10，各一个变快点。
        assertThat(basePointCount).isEqualTo(26);
    }

    @Test
    void assignsStableRowIdsRegardlessOfProviderOrder() {
        HistorySnapshot first = build(basePayload(), DECISION_AT);

        ObjectNode reversed = doc();
        addBet365(reversed);
        addPinnacle(reversed);
        HistorySnapshot second = build(reversed, DECISION_AT);

        assertThat(describe(second.rows())).isEqualTo(describe(first.rows()));
        assertThat(first.rows()).allSatisfy(row -> assertThat(row.getId()).isPositive());
    }

    @Test
    void neverSurfacesQuoteLaterThanDecisionAt() {
        HistorySnapshot snapshot = build(basePayload(), DECISION_AT);

        assertThat(snapshot.rows()).allSatisfy(row ->
                assertThat(row.getCapturedAt()).isBeforeOrEqualTo(DECISION_AT));
        assertThat(snapshot.handicapQuotes()).allSatisfy(quote ->
                assertThat(quote.capturedAt()).isBeforeOrEqualTo(DECISION_AT));
        assertThat(snapshot.totalsQuotes()).allSatisfy(quote ->
                assertThat(quote.capturedAt()).isBeforeOrEqualTo(DECISION_AT));
        assertThat(snapshot.latestQuoteAt()).hasValueSatisfying(at ->
                assertThat(at).isBeforeOrEqualTo(DECISION_AT));
    }

    @Test
    void readsNumericAndTextOddsAlike() {
        ObjectNode textOdds = doc();
        ObjectNode textHandicap = market(provider(textOdds, "平博"), 4);
        option(textHandicap, "Home", "0", "2026-10-23 14:00:00|1.87");
        option(textHandicap, "Away", "0", "2026-10-23 14:00:00|2.03");

        ObjectNode numericOdds = doc();
        ObjectNode numericHandicap = market(provider(numericOdds, "平博"), 4);
        numericOption(numericHandicap, "Home", "0", "2026-10-23 14:00:00", "1.87");
        numericOption(numericHandicap, "Away", "0", "2026-10-23 14:00:00", "2.03");

        assertThat(build(numericOdds, DECISION_AT).handicapQuotes())
                .isEqualTo(build(textOdds, DECISION_AT).handicapQuotes());
    }

    @Test
    void acceptsEnvelopeWithDataNode() {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("code", 200);
        envelope.set("data", basePayload());

        assertThat(build(envelope, DECISION_AT).rows())
                .isEqualTo(build(basePayload(), DECISION_AT).rows());
    }

    @Test
    void rejectsMissingRequiredContext() {
        assertThatThrownBy(() -> builder.build(basePayload(), null, MATCH_ID, PROVIDER, HASH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.build(basePayload(), DECISION_AT, null, PROVIDER, HASH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.build(null, DECISION_AT, MATCH_ID, PROVIDER, HASH))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toleratesKickoffAgnosticPayloadWithoutProviders() {
        HistorySnapshot snapshot = build(MAPPER.createObjectNode(), DECISION_AT);

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(snapshot.bookmakerCount()).isZero();
        assertThat(snapshot.pointCount()).isZero();
        assertThat(snapshot.latestQuoteAt()).isEmpty();
    }

    // ---- 构造辅助 ----

    private HistorySnapshot build(JsonNode payload, Instant decisionAt) {
        return builder.build(payload, decisionAt, MATCH_ID, PROVIDER, HASH);
    }

    private static ObjectNode doc() {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("matchId", "2042155");
        doc.put("updateTime", "2026-10-23 15:14:21");
        doc.putArray("providers");
        return doc;
    }

    private static ObjectNode provider(ObjectNode doc, String name) {
        ObjectNode provider = doc.withArray("providers").addObject();
        provider.put("name", name);
        provider.putArray("markets");
        return provider;
    }

    private static ObjectNode market(ObjectNode provider, int marketId) {
        ObjectNode market = provider.withArray("markets").addObject();
        market.put("marketId", marketId);
        market.putArray("options");
        return market;
    }

    /** {@code points} 用 {@code "yyyy-MM-dd HH:mm:ss|odd"} 书写，与源侧文本形态一致。 */
    private static ObjectNode option(ObjectNode market, String side, String line, String... points) {
        ObjectNode option = market.withArray("options").addObject();
        option.put("option", side);
        option.put("line", line);
        ArrayNode array = option.putArray("points");
        for (String point : points) {
            String[] parts = point.split("\\|", 2);
            ObjectNode node = array.addObject();
            node.put("updateTime", parts[0]);
            node.put("odd", parts[1]);
        }
        return option;
    }

    private static void numericOption(ObjectNode market, String side, String line, String time, String odd) {
        ObjectNode option = market.withArray("options").addObject();
        option.put("option", side);
        option.put("line", line);
        ArrayNode array = option.putArray("points");
        ObjectNode node = array.addObject();
        node.put("updateTime", time);
        node.put("odd", new BigDecimal(odd));
    }

    /** 平博 2 档让球 + 5 档大小球、Bet365 1 档让球，全部报价落在 06:00Z。 */
    private static ObjectNode basePayload() {
        ObjectNode payload = doc();
        addPinnacle(payload);
        addBet365(payload);
        return payload;
    }

    private static void addPinnacle(ObjectNode payload) {
        ObjectNode pinnacle = provider(payload, "平博");
        ObjectNode handicap = market(pinnacle, 4);
        option(handicap, "Home", "0", "2026-10-23 14:00:00|1.95");
        option(handicap, "Away", "0", "2026-10-23 14:00:00|2.05");
        option(handicap, "Home", "-0.5", "2026-10-23 14:00:00|1.90");
        option(handicap, "Away", "-0.5", "2026-10-23 14:00:00|2.10");

        ObjectNode totals = market(pinnacle, 5);
        totalsOption(totals, "2.5", "1.85", "1.95");
        totalsOption(totals, "2.75", "1.90", "1.90");
        totalsOption(totals, "3.0", "1.95", "1.85");
        totalsOption(totals, "3.25", "2.00", "1.80");
        totalsOption(totals, "3.5", "2.05", "1.75");
    }

    private static void addBet365(ObjectNode payload) {
        ObjectNode bet365 = provider(payload, "Bet365");
        ObjectNode handicap = market(bet365, 4);
        option(handicap, "Home", "-0.25", "2026-10-23 14:00:00|1.92");
        option(handicap, "Away", "-0.25", "2026-10-23 14:00:00|1.98");
        ObjectNode totals = market(bet365, 5);
        totalsOption(totals, "2.5", "1.88", "1.92");
        totalsOption(totals, "2.75", "1.91", "1.89");
        totalsOption(totals, "3.0", "1.94", "1.86");
        totalsOption(totals, "3.25", "1.98", "1.82");
        totalsOption(totals, "3.5", "2.02", "1.78");
    }

    private static void totalsOption(ObjectNode market, String line, String over, String under) {
        option(market, "Over", line, "2026-10-23 14:00:00|" + over);
        option(market, "Under", line, "2026-10-23 14:00:00|" + under);
    }

    /** 伪行身份 = id + 庄家 + 两条线，用来比对「顺序变化不影响编号」。 */
    private static List<String> describe(List<AsianOddsSnapshot> rows) {
        return rows.stream()
                .map(row -> row.getId() + "|" + row.getBookmakerCode()
                        + "|" + row.getHandicapLine() + "|" + row.getTotalLine())
                .toList();
    }
}
