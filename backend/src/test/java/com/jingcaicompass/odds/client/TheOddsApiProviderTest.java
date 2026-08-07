package com.jingcaicompass.odds.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpExecutor;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class TheOddsApiProviderTest {

    private MockWebServer server;
    private TheOddsApiProvider provider;
    private AsianOddsPayloadMapper payloadMapper;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        payloadMapper = new AsianOddsPayloadMapper(objectMapper);
        AsianOddsProviderProperties properties = new AsianOddsProviderProperties(
                AsianOddsProviderTypeEnum.THE_ODDS_API,
                URI.create(server.url("/").toString()),
                "odds-api-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                new AsianOddsProviderProperties.RetryProperties(2, Duration.ZERO),
                0,
                new AsianOddsProviderProperties.TheOddsProperties(Map.of("英超", "soccer_epl"), 400)
        );
        provider = new TheOddsApiProvider(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                new ProviderHttpExecutor(duration -> { }),
                properties,
                payloadMapper,
                objectMapper
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void aggregatesRawResponsesUsesQueryApiKeyAndParsesPairedSpreads() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("x-requests-remaining", "499")
                .addHeader("x-requests-used", "1")
                .addHeader("x-requests-last", "1")
                .setBody("""
                        [{
                          "id":"event-1", "sport_key":"soccer_epl",
                          "commence_time":"2026-08-01T12:00:00Z",
                          "home_team":"Home", "away_team":"Away",
                          "bookmakers":[{
                            "key":"pinnacle", "last_update":"2026-08-01T09:00:00Z",
                            "markets":[{"key":"spreads", "last_update":"2026-08-01T09:01:00Z",
                              "outcomes":[
                                {"name":"Home", "price":1.91, "point":-0.5},
                                {"name":"Away", "price":1.95, "point":0.5}
                              ]
                            }, {"key":"totals", "last_update":"2026-08-01T09:02:00Z",
                              "outcomes":[
                                {"name":"Over", "price":1.87, "point":2.5},
                                {"name":"Under", "price":1.99, "point":2.5}
                              ]
                            }]
                          }]
                        }]
                        """));

        var raw = provider.fetchPreMatchOddsRaw(query("soccer_epl"));

        assertThat(raw.quotaCost()).isEqualTo(1);
        assertThat(raw.payloadJson()).contains("\"responses\"").contains("soccer_epl");
        assertThat(raw.payloadJson()).doesNotContain("odds-api-secret");
        assertThat(raw.requestKey()).contains("spreads,totals");
        assertThat(raw.requestKey()).doesNotContain("odds-api-secret");
        var matches = payloadMapper.parseMatches(raw.payloadJson());
        assertThat(matches).hasSize(1);
        var match = matches.getFirst();
        assertThat(match.providerLeagueId()).isEqualTo("soccer_epl");
        assertThat(match.parseError()).isNull();
        assertThat(match.lines()).singleElement().satisfies(line -> {
            assertThat(line.handicapLine()).isEqualByComparingTo("-0.5");
            assertThat(line.homeOdds()).isEqualByComparingTo("1.91");
            assertThat(line.awayOdds()).isEqualByComparingTo("1.95");
            assertThat(line.totalLine()).isEqualByComparingTo("2.5");
            assertThat(line.overOdds()).isEqualByComparingTo("1.87");
            assertThat(line.underOdds()).isEqualByComparingTo("1.99");
        });

        var request = server.takeRequest();
        assertThat(request.getPath()).startsWith("/v4/sports/soccer_epl/odds?");
        assertThat(request.getRequestUrl().queryParameter("apiKey")).isEqualTo("odds-api-secret");
        assertThat(request.getRequestUrl().queryParameter("regions")).isEqualTo("eu");
        assertThat(request.getRequestUrl().queryParameter("markets")).isEqualTo("spreads,totals");
        assertThat(request.getRequestUrl().queryParameter("commenceTimeFrom"))
                .isEqualTo("2026-08-01T00:00:00Z");
        assertThat(request.getRequestUrl().queryParameter("commenceTimeTo"))
                .isEqualTo("2026-08-02T00:00:00Z");
        assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void marksInconsistentSpreadPairAsControlledParseFailure() {
        String payload = """
                {"provider":"THE_ODDS_API","responses":[{"sportKey":"soccer_epl","body":[{
                  "id":"event-2", "home_team":"Home", "away_team":"Away",
                  "commence_time":"2026-08-01T12:00:00Z",
                  "bookmakers":[{"key":"book", "markets":[{"key":"spreads", "outcomes":[
                    {"name":"Home", "price":1.9, "point":-0.5},
                    {"name":"Away", "price":1.9, "point":-0.25}
                  ]}]}]
                }]}]}
                """;

        var matches = payloadMapper.parseMatches(payload);
        assertThat(matches).hasSize(1);
        var match = matches.getFirst();

        assertThat(match.lines()).isEmpty();
        assertThat(match.parseError()).isEqualTo("INCONSISTENT_SPREAD_PAIR");
    }

    @Test
    void marksInconsistentTotalsPairAsControlledParseFailure() {
        String payload = """
                {"provider":"THE_ODDS_API","responses":[{"sportKey":"soccer_epl","body":[{
                  "id":"event-3", "home_team":"Home", "away_team":"Away",
                  "commence_time":"2026-08-01T12:00:00Z",
                  "bookmakers":[{"key":"book", "markets":[
                    {"key":"spreads", "outcomes":[
                      {"name":"Home", "price":1.9, "point":-0.5},
                      {"name":"Away", "price":1.9, "point":0.5}
                    ]},
                    {"key":"totals", "outcomes":[
                      {"name":"Over", "price":1.9, "point":2.5},
                      {"name":"Under", "price":1.9, "point":2.25}
                    ]}
                  ]}]
                }]}]}
                """;

        var matches = payloadMapper.parseMatches(payload);

        assertThat(matches).hasSize(1);
        var match = matches.getFirst();
        assertThat(match.lines()).isEmpty();
        assertThat(match.parseError()).isEqualTo("INCONSISTENT_TOTALS_PAIR");
    }

    @Test
    void doesNotCallUpstreamWhenNoConfiguredSportKeyExists() {
        var raw = provider.fetchPreMatchOddsRaw(new AsianOddsQueryDto(null, null, null, null));

        assertThat(raw.quotaCost()).isZero();
        assertThat(payloadMapper.parseMatches(raw.payloadJson())).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void sanitizesApiKeyFromUpstreamFailure() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("limited"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("limited"));

        assertThatThrownBy(() -> provider.fetchPreMatchOddsRaw(query("soccer_epl")))
                .isInstanceOf(ProviderHttpException.class)
                .hasMessageNotContaining("odds-api-secret")
                .hasMessageNotContaining("apiKey");
    }

    private AsianOddsQueryDto query(String sportKey) {
        return new AsianOddsQueryDto(
                null,
                OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-02T00:00:00Z"),
                null,
                java.util.List.of(sportKey)
        );
    }
}
