package com.jingcaicompass.match.infrastructure.sporttery;

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
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChinaSportteryProviderTest {

    @Test
    void mapsVerifiedOfficialResponseAndFiltersByLotteryDate() throws IOException {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        String baseUrl = "https://webapi.sporttery.cn";
        server.expect(once(), requestTo(baseUrl + ChinaSportteryProvider.MATCH_CALCULATOR_PATH))
                .andExpect(header(HttpHeaders.REFERER, "https://www.sporttery.cn/jc/jsq/zqspf/"))
                .andRespond(withSuccess(readFixture(), MediaType.APPLICATION_JSON));
        RestClient restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.REFERER, "https://www.sporttery.cn/jc/jsq/zqspf/")
                .build();
        ChinaSportteryProvider provider = new ChinaSportteryProvider(restClient);

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
        server.verify();
    }

    private String readFixture() throws IOException {
        return new ClassPathResource("sporttery/get-match-calculator-success.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
