package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.dto.AsianOddsLeagueDto;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AsianOddsProviderContractTest {

    @Test
    void contractAllowsTestDoubleWithoutHttp() {
        AsianOddsProvider provider = new AsianOddsProvider() {
            @Override
            public String providerCode() {
                return "CONTRACT_STUB";
            }

            @Override
            public List<AsianOddsLeagueDto> fetchLeagues() {
                return List.of(new AsianOddsLeagueDto("epl", "英超", "soccer"));
            }

            @Override
            public List<AsianOddsMatchOddsDto> fetchPreMatchOdds(AsianOddsQueryDto query) {
                return List.of(new AsianOddsMatchOddsDto(
                        "match-1",
                        "主队",
                        "客队",
                        OffsetDateTime.parse("2026-07-22T19:30:00+08:00"),
                        false,
                        List.of(new AsianOddsLineDto(
                                "pinnacle",
                                new BigDecimal("-0.5"),
                                new BigDecimal("1.90"),
                                new BigDecimal("1.95"),
                                OffsetDateTime.parse("2026-07-22T12:00:00+08:00")
                        ))
                ));
            }
        };

        AsianOddsQueryDto query = new AsianOddsQueryDto(
                "epl",
                OffsetDateTime.parse("2026-07-22T00:00:00+08:00"),
                OffsetDateTime.parse("2026-07-23T00:00:00+08:00"),
                null
        );

        assertThat(provider.providerCode()).isEqualTo("CONTRACT_STUB");
        assertThat(provider.fetchLeagues()).singleElement().extracting(AsianOddsLeagueDto::leagueId)
                .isEqualTo("epl");
        assertThat(provider.fetchPreMatchOdds(query)).singleElement().satisfies(match -> {
            assertThat(match.providerMatchId()).isEqualTo("match-1");
            assertThat(match.lines()).singleElement().extracting(AsianOddsLineDto::handicapLine)
                    .isEqualTo(new BigDecimal("-0.5"));
        });
    }
}
