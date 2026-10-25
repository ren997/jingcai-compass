package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NovaAPI 赔率解析器守卫。
 *
 * <p>样本是 2026-10-21 的真实抓包（{@code tools/novaapi-probe}），这是唯一能证明「解析口径与
 * 真实响应一致」的证据。当前账号的密钥是日卡、已过期，因此本测试必须能在完全离线下跑通——
 * 一旦它开始依赖网络，就等于把解析正确性绑在一张随时失效的卡上。</p>
 */
class NovaApiOddsPayloadMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EVENT_2492881 = "asianodds/novaapi-2492881-tf-odds.json";
    private static final String EVENT_2407136 = "asianodds/novaapi-2407136-tf-odds.json";
    private static final String EVENT_2594567 = "asianodds/novaapi-2594567-tf-odds.json";

    private final NovaApiOddsPayloadMapper mapper = new NovaApiOddsPayloadMapper();

    @Test
    void expandsHandicapTimesKeptTotalsLines() {
        // 行数 = Σ各庄(完整让球档数 × 该庄落在「最平衡 4 档」内的大小球档数)。
        // 这三个数字来自真实抓包的独立复算，改动保留档策略会立刻反映到这里。
        assertThat(linesOf(EVENT_2492881, "2042106")).hasSize(130);
        assertThat(linesOf(EVENT_2407136, "2041974")).hasSize(108);
        assertThat(linesOf(EVENT_2594567, "2042099")).hasSize(156);
    }

    @Test
    void keepsTotalsLinesAroundTheRealMainLineNotTheMostReportedOnes() {
        AsianOddsMatchOddsDto match = parse(EVENT_2492881, "2042106");

        // 平博主盘在 2.75（夹具里 mostBalancedLine 的结论）。按「报出家数」选档会把它排掉，
        // 因为 1.5/2.5/3.5/4.5 才是每家都报的整数盘。
        assertThat(totalsLinesOf(match, "pinnacle")).contains(2.5d, 2.75d, 3.0d);
        assertThat(totalsLinesOf(match, "pinnacle")).hasSize(NovaApiOddsPayloadMapper.TOTALS_LINES_KEPT);
    }

    @Test
    void mapsChineseBookmakerNamesToStableAsciiCodes() {
        AsianOddsMatchOddsDto match = parse(EVENT_2407136, "2041974");

        assertThat(bookmakersOf(match)).contains("betfair", "pinnacle", "bet365", "betvictor");
        // 威廉希尔在这场一条亚盘都没报，不该凭空出现。
        assertThat(bookmakersOf(match)).doesNotContain("williamhill");
    }

    @Test
    void writesHandicapLineAsHomeHandicapWithoutFlippingSign() {
        AsianOddsMatchOddsDto match = parse(EVENT_2492881, "2042106");

        // line 是「主队让球数」：-0.25 表示主让 0.25，主队赔率 1.88 < 客队 1.97。
        // 若照抄 The Odds API 的「两侧互为相反数」假设，这里会整批判不成立并零条落库。
        AsianOddsLineDto minusQuarter = match.lines().stream()
                .filter(line -> "pinnacle".equals(line.bookmakerCode()))
                .filter(line -> line.handicapLine().compareTo(new BigDecimal("-0.25")) == 0)
                .findFirst()
                .orElseThrow();
        assertThat(minusQuarter.homeOdds()).isEqualByComparingTo("1.88");
        assertThat(minusQuarter.awayOdds()).isEqualByComparingTo("1.97");
    }

    @Test
    void everyRowCarriesACompleteHandicapPair() {
        AsianOddsMatchOddsDto match = parse(EVENT_2594567, "2042099");

        assertThat(match.lines()).isNotEmpty();
        assertThat(match.lines()).allSatisfy(line -> {
            assertThat(line.bookmakerCode()).isNotBlank();
            assertThat(line.handicapLine()).isNotNull();
            assertThat(line.homeOdds()).isGreaterThan(BigDecimal.ONE);
            assertThat(line.awayOdds()).isGreaterThan(BigDecimal.ONE);
            // 大小球要么三字段齐全，要么全空（只报让球的庄）。
            boolean complete = line.totalLine() != null && line.overOdds() != null && line.underOdds() != null;
            boolean empty = line.totalLine() == null && line.overOdds() == null && line.underOdds() == null;
            assertThat(complete || empty).isTrue();
        });
    }

    @Test
    void dropsSuspendedSideAndUnpairedLines() {
        ObjectNode root = envelope("m-1");
        ObjectNode data = bodyOf(pushResponse(root, "m-1"));
        ArrayNode providers = data.putArray("providers");
        ObjectNode provider = providers.addObject().put("providerId", 4).put("name", "平博");
        ArrayNode markets = provider.putArray("markets");
        ObjectNode market = markets.addObject().put("marketId", 4).put("name", "亚盘让球");
        ArrayNode options = market.putArray("options");
        options.addObject().put("option", "Home").put("line", "-0.25").put("odd", 1.88)
                .put("updateTime", "2026-10-21 11:04:19");
        options.addObject().put("option", "Away").put("line", "-0.25").put("odd", 1.97)
                .put("suspended", true).put("updateTime", "2026-10-21 11:04:19");
        // 只有单侧的档位：-0.5 只有 Home，配不成对必须丢弃。
        options.addObject().put("option", "Home").put("line", "-0.5").put("odd", 2.16)
                .put("updateTime", "2026-10-21 11:04:19");
        // 空 line 的档位（胜平负残留）必须忽略。
        options.addObject().put("option", "Home").put("line", "").put("odd", 3.10)
                .put("updateTime", "2026-10-21 11:04:19");

        AsianOddsMatchOddsDto match = mapper.parse(root).get(0);

        assertThat(match.lines()).isEmpty();
        assertThat(match.parseError()).isNull();
    }

    @Test
    void keepsHandicapOnlyRowsWhenBookmakerReportsNoTotals() {
        ObjectNode root = envelope("m-2");
        ObjectNode data = bodyOf(pushResponse(root, "m-2"));
        ObjectNode provider = data.putArray("providers").addObject().put("name", "韦德");
        ObjectNode market = provider.putArray("markets").addObject().put("marketId", 4);
        ArrayNode options = market.putArray("options");
        options.addObject().put("option", "Home").put("line", "-0.75").put("odd", 2.44);
        options.addObject().put("option", "Away").put("line", "-0.75").put("odd", 1.47);

        AsianOddsMatchOddsDto match = mapper.parse(root).get(0);

        assertThat(match.lines()).singleElement().satisfies(line -> {
            assertThat(line.bookmakerCode()).isEqualTo("betvictor");
            assertThat(line.handicapLine()).isEqualByComparingTo("-0.75");
            assertThat(line.totalLine()).isNull();
            assertThat(line.overOdds()).isNull();
            assertThat(line.underOdds()).isNull();
        });
    }

    @Test
    void surfacesUpstreamNonOkCodeAsParseError() {
        ObjectNode root = envelope("m-3");
        ObjectNode response = pushResponse(root, "m-3");
        response.putObject("body").put("code", 401).put("message", "API密钥无效或已过期");

        AsianOddsMatchOddsDto match = mapper.parse(root).get(0);

        assertThat(match.lines()).isEmpty();
        assertThat(match.parseError()).contains("UPSTREAM_CODE_401");
    }

    @Test
    void rejectsPayloadWithoutResponsesArray() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("provider", NovaApiOddsPayloadMapper.PROVIDER_CODE);
        root.putArray("data");

        assertThatThrownBy(() -> mapper.parse(root))
                .hasMessageContaining("responses");
    }

    @Test
    void fallsBackToLowercaseRawNameForUnknownBookmaker() {
        ObjectNode root = envelope("m-4");
        ObjectNode data = bodyOf(pushResponse(root, "m-4"));
        ObjectNode provider = data.putArray("providers").addObject().put("name", "UnknownBook");
        ObjectNode market = provider.putArray("markets").addObject().put("marketId", 4);
        ArrayNode options = market.putArray("options");
        options.addObject().put("option", "Home").put("line", "0").put("odd", 1.95);
        options.addObject().put("option", "Away").put("line", "0").put("odd", 1.95);

        // 未登记的庄名回退为小写原文：宁可入库成陌生编码，也不能静默命中 pinnacle 白名单。
        assertThat(mapper.parse(root).get(0).lines()).singleElement()
                .satisfies(line -> assertThat(line.bookmakerCode()).isEqualTo("unknownbook"));
    }

    private List<AsianOddsLineDto> linesOf(String resourceName, String matchId) {
        return parse(resourceName, matchId).lines();
    }

    private AsianOddsMatchOddsDto parse(String resourceName, String matchId) {
        ObjectNode root = envelope(matchId);
        ObjectNode response = pushResponse(root, matchId);
        response.set("body", readTree(resourceName));
        List<AsianOddsMatchOddsDto> matches = mapper.parse(root);
        assertThat(matches).singleElement();
        return matches.get(0);
    }

    private static List<String> bookmakersOf(AsianOddsMatchOddsDto match) {
        return match.lines().stream().map(AsianOddsLineDto::bookmakerCode).distinct().toList();
    }

    private static List<Double> totalsLinesOf(AsianOddsMatchOddsDto match, String bookmakerCode) {
        return match.lines().stream()
                .filter(line -> bookmakerCode.equals(line.bookmakerCode()))
                .map(AsianOddsLineDto::totalLine)
                .filter(Objects::nonNull)
                .map(BigDecimal::doubleValue)
                .distinct()
                .sorted()
                .toList();
    }

    private static ObjectNode envelope(String matchId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("provider", NovaApiOddsPayloadMapper.PROVIDER_CODE);
        root.putArray("responses");
        return root;
    }

    private static ObjectNode pushResponse(ObjectNode root, String matchId) {
        ObjectNode response = ((ArrayNode) root.get("responses")).addObject();
        response.put("matchId", matchId);
        response.put("status", 200);
        response.putObject("body").put("code", 200).putObject("data");
        return response;
    }

    private static ObjectNode bodyOf(ObjectNode response) {
        return (ObjectNode) response.get("body").get("data");
    }

    private static JsonNode readTree(String resourceName) {
        try (InputStream stream = NovaApiOddsPayloadMapperTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("fixture not found on classpath: " + resourceName);
            }
            return MAPPER.readTree(stream);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read fixture: " + resourceName, exception);
        }
    }
}
