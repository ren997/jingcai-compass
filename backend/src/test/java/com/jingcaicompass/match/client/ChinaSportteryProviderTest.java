package com.jingcaicompass.match.client;

import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChinaSportteryProviderTest {

    @Test
    void mapsVerifiedOfficialResponseAndFiltersByLotteryDate() throws IOException {
        ChinaSportteryProvider provider = providerWithBody(readFixture());

        var matches = provider.findDailyMatches(LocalDate.of(2026, 7, 22));

        assertThat(provider.providerCode()).isEqualTo("CHINA_SPORTTERY");
        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.matchId()).isEqualTo("sporttery-2040584");
            assertThat(match.lotteryMatchNo()).isEqualTo("周三201");
            assertThat(match.leagueName()).isEqualTo("韩职");
            assertThat(match.homeTeamName()).isEqualTo("首尔FC");
            assertThat(match.awayTeamName()).isEqualTo("浦项制铁");
            assertThat(match.kickoffTime().toString()).isEqualTo("2026-07-22T18:30+08:00");
            assertThat(match.officialHandicap()).isEqualTo(-1);
        });
    }

    @Test
    void returnsEmptyListWhenMatchPoolHasNoGroups() {
        ChinaSportteryProvider provider = providerWithBody("""
                {"success":true,"errorCode":"0","errorMessage":"","value":{"matchInfoList":[]}}
                """);

        assertThat(provider.findDailyMatches(LocalDate.of(2026, 7, 22))).isEmpty();
    }

    @Test
    void throwsUpstreamFailureWhenOfficialResponseReportsError() {
        ChinaSportteryProvider provider = providerWithBody("""
                {"success":false,"errorCode":"500","errorMessage":"upstream busy","value":null}
                """);

        assertThatThrownBy(() -> provider.findDailyMatches(LocalDate.of(2026, 7, 22)))
                .isInstanceOf(SportteryDataAccessException.class)
                .satisfies(exception -> {
                    SportteryDataAccessException dataAccessException = (SportteryDataAccessException) exception;
                    assertThat(dataAccessException.category()).isEqualTo(ProviderErrorCategory.UPSTREAM_FAILURE);
                    assertThat(dataAccessException.errorCode()).isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
                });
    }

    @Test
    void throwsParseFailureWhenHandicapIsInvalid() {
        ChinaSportteryProvider provider = providerWithBody("""
                {
                  "success": true,
                  "errorCode": "0",
                  "errorMessage": "",
                  "value": {
                    "matchInfoList": [
                      {
                        "businessDate": "2026-07-22",
                        "subMatchList": [
                          {
                            "matchId": 1,
                            "matchNumStr": "周三001",
                            "leagueAbbName": "演示联赛",
                            "homeTeamAbbName": "主队",
                            "awayTeamAbbName": "客队",
                            "matchDate": "2026-07-22",
                            "matchTime": "19:30:00",
                            "matchStatus": "Selling",
                            "hhad": { "goalLine": "not-a-number" }
                          }
                        ]
                      }
                    ]
                  }
                }
                """);

        assertThatThrownBy(() -> provider.findDailyMatches(LocalDate.of(2026, 7, 22)))
                .isInstanceOf(SportteryDataAccessException.class)
                .satisfies(exception -> {
                    SportteryDataAccessException dataAccessException = (SportteryDataAccessException) exception;
                    assertThat(dataAccessException.category()).isEqualTo(ProviderErrorCategory.PARSE_FAILURE);
                    assertThat(dataAccessException.errorCode()).isEqualTo(ErrorCode.DATA_SOURCE_PARSE_FAILED);
                });
    }

    @Test
    void throwsParseFailureWhenMatchStatusMissing() {
        ChinaSportteryProvider provider = providerWithBody("""
                {
                  "success": true,
                  "errorCode": "0",
                  "errorMessage": "",
                  "value": {
                    "matchInfoList": [
                      {
                        "businessDate": "2026-07-22",
                        "subMatchList": [
                          {
                            "matchId": 1,
                            "matchNumStr": "周三001",
                            "leagueAbbName": "演示联赛",
                            "homeTeamAbbName": "主队",
                            "awayTeamAbbName": "客队",
                            "matchDate": "2026-07-22",
                            "matchTime": "19:30:00",
                            "matchStatus": "",
                            "hhad": { "goalLine": "-1" }
                          }
                        ]
                      }
                    ]
                  }
                }
                """);

        assertThatThrownBy(() -> provider.findDailyMatches(LocalDate.of(2026, 7, 22)))
                .isInstanceOf(SportteryDataAccessException.class)
                .satisfies(exception -> {
                    SportteryDataAccessException dataAccessException = (SportteryDataAccessException) exception;
                    assertThat(dataAccessException.category()).isEqualTo(ProviderErrorCategory.PARSE_FAILURE);
                });
    }

    private ChinaSportteryProvider providerWithBody(String body) {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        String baseUrl = "https://webapi.sporttery.cn";
        server.expect(once(), requestTo(baseUrl + ChinaSportteryProvider.MATCH_CALCULATOR_PATH))
                .andExpect(header(HttpHeaders.REFERER, "https://www.sporttery.cn/jc/jsq/zqspf/"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        RestClient restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.REFERER, "https://www.sporttery.cn/jc/jsq/zqspf/")
                .build();
        return new ChinaSportteryProvider(restClient);
    }

    private String readFixture() throws IOException {
        return new ClassPathResource("sporttery/get-match-calculator-success.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
