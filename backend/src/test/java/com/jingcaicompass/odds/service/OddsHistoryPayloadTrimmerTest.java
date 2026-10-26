package com.jingcaicompass.odds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 归档裁剪的口径：只留让球与大小球，并把「采到了什么」量化成可校验的四个统计量。
 *
 * <p>这里刻意不测「时间序列怎么切」——那是 {@link OddsHistorySnapshotBuilder} 的职责。
 * 裁剪器只回答两个问题：<b>这份载荷里有没有可用序列</b>，以及<b>序列的两端在哪</b>。</p>
 */
class OddsHistoryPayloadTrimmerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OddsHistoryPayloadTrimmer trimmer = new OddsHistoryPayloadTrimmer();

    @Test
    void keepsOnlyHandicapAndTotalsMarkets() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {"code":200,"msg":"ok","data":{"providers":[
                  {"name":"平博","markets":[
                    {"marketId":1,"options":[{"option":"Home","points":[{"odd":2.1,"updateTime":"2026-06-20 10:00:00"}]}]},
                    {"marketId":4,"options":[
                      {"option":"Home","line":-0.25,"points":[{"odd":1.9,"updateTime":"2026-06-20 12:00:00"}]},
                      {"option":"Away","line":-0.25,"points":[{"odd":2.0,"updateTime":"2026-06-20 12:00:00"}]}
                    ]},
                    {"marketId":5,"options":[
                      {"option":"Over","line":3.0,"points":[{"odd":1.99,"updateTime":"2026-06-20 14:00:00"}]},
                      {"option":"Under","line":3.0,"points":[{"odd":1.9,"updateTime":"2026-06-20 14:00:00"}]}
                    ]}
                  ]}
                ]}}
                """);

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(body);

        assertThat(trimmed.trimmed()).as("确实丢了胜平负市场").isTrue();
        assertThat(trimmed.bookmakerCount()).isEqualTo(1);
        assertThat(trimmed.pointCount()).as("只数让球与大小球的点").isEqualTo(4);
        assertThat(marketIds(trimmed.payload()))
                .as("只保留 marketId 4 与 5")
                .containsExactly(4, 5);
        assertThat(providerNames(trimmed.payload())).containsExactly("平博");
    }

    @Test
    void unparseableUpdateTimeStillCountsAsPointButNotAsSeriesEdge() throws Exception {
        // 首点时间不可解析：它仍然落库（切片器会跳过它），但不能伪造序列起点。
        JsonNode body = objectMapper.readTree("""
                {"data":{"providers":[{"name":"平博","markets":[
                  {"marketId":4,"options":[
                    {"option":"Home","line":0,"points":[
                      {"odd":1.9,"updateTime":"not-a-time"},
                      {"odd":1.88,"updateTime":"2026-06-20 12:00:00"},
                      {"odd":1.86,"updateTime":"2026-06-21 12:00:00"}
                    ]}
                  ]}
                ]}]}}
                """);

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(body);

        assertThat(trimmed.pointCount()).isEqualTo(3);
        assertThat(trimmed.hasSeries()).isTrue();
        assertThat(trimmed.seriesStartAt()).as("不可解析的点不能当起点")
                .isEqualTo(Instant.parse("2026-06-20T04:00:00Z"));
        assertThat(trimmed.seriesEndAt()).isEqualTo(Instant.parse("2026-06-21T04:00:00Z"));
    }

    @Test
    void naiveUpdateTimeIsInterpretedAsBeijingNotUtc() throws Exception {
        // 这是最容易错的一处：同一串「2026-06-20 12:00:00」按 UTC 解释会差 8 小时，
        // 而回测的「赛前 105 分钟」会因此变成「赛前 585 分钟」，且断言照样通过。
        JsonNode body = objectMapper.readTree("""
                {"data":{"providers":[{"name":"平博","markets":[
                  {"marketId":4,"options":[
                    {"option":"Home","line":0,"points":[{"odd":1.9,"updateTime":"2026-06-20 12:00:00"}]}
                  ]}
                ]}]}}
                """);

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(body);

        assertThat(trimmed.seriesStartAt())
                .as("北京时间 12:00 等于 UTC 04:00")
                .isEqualTo(Instant.parse("2026-06-20T04:00:00Z"));
    }

    @Test
    void providerWithoutKeptMarketsIsDroppedAndCountedAsTrimmed() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {"data":{"providers":[
                  {"name":"只有胜平负","markets":[
                    {"marketId":1,"options":[{"option":"Home","points":[{"odd":2.1,"updateTime":"2026-06-20 10:00:00"}]}]}
                  ]},
                  {"name":"平博","markets":[
                    {"marketId":4,"options":[{"option":"Home","line":0,"points":[{"odd":1.9,"updateTime":"2026-06-20 12:00:00"}]}]}
                  ]}
                ]}}
                """);

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(body);

        assertThat(trimmed.bookmakerCount()).as("只剩报了亚盘的庄").isEqualTo(1);
        assertThat(providerNames(trimmed.payload())).containsExactly("平博");
        assertThat(trimmed.trimmed()).isTrue();
    }

    @Test
    void emptyProvidersYieldsEmptyPayloadNotSeries() throws Exception {
        JsonNode body = objectMapper.readTree("{\"code\":200,\"msg\":\"ok\",\"data\":{\"providers\":[]}}");

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(body);

        assertThat(trimmed.hasSeries()).isFalse();
        assertThat(trimmed.isEmptyPayload()).isTrue();
        assertThat(trimmed.payload()).as("空载荷也要能序列化后落库成负缓存").isNotNull();
        assertThat(trimmed.trimmed()).as("空载荷没有被裁掉任何东西").isFalse();
    }

    @Test
    void marketWithNoOptionsIsDropped() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {"data":{"providers":[{"name":"平博","markets":[
                  {"marketId":4,"options":[]},
                  {"marketId":5,"options":[{"option":"Over","line":2.5,"points":[{"odd":1.9,"updateTime":"2026-06-20 12:00:00"}]}]}
                ]}]}}
                """);

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(body);

        assertThat(marketIds(trimmed.payload())).containsExactly(5);
    }

    @Test
    void payloadWithoutDataEnvelopeIsTrimmedInPlace() throws Exception {
        // 切片器同时接受「带 data 信封」与「data 节点本身」，裁剪器必须两种都处理，
        // 否则从归档里读出来的（没有信封的）形态会在重裁时静默丢掉 providers。
        JsonNode body = objectMapper.readTree("""
                {"providers":[{"name":"平博","markets":[
                  {"marketId":4,"options":[{"option":"Home","line":0,"points":[{"odd":1.9,"updateTime":"2026-06-20 12:00:00"}]}]}
                ]}]}
                """);

        OddsHistoryPayloadTrimmer.Trimmed trimmed = trimmer.trim(body);

        assertThat(trimmed.payload().path("providers").size()).isEqualTo(1);
        assertThat(trimmed.payload().path("data").isMissingNode()).isTrue();
    }

    private static java.util.List<Integer> marketIds(JsonNode payload) {
        JsonNode providers = payload.path("data").isObject()
                ? payload.path("data").path("providers")
                : payload.path("providers");
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (JsonNode provider : providers) {
            for (JsonNode market : provider.path("markets")) {
                ids.add(market.path("marketId").asInt());
            }
        }
        return ids;
    }

    private static java.util.List<String> providerNames(JsonNode payload) {
        JsonNode providers = payload.path("data").isObject()
                ? payload.path("data").path("providers")
                : payload.path("providers");
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode provider : providers) {
            names.add(provider.path("name").asText());
        }
        return names;
    }
}
