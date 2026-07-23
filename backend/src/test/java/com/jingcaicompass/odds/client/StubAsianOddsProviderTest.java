package com.jingcaicompass.odds.client;

import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class StubAsianOddsProviderTest {

    private final StubAsianOddsProvider provider = new StubAsianOddsProvider();

    @Test
    void loadsLeaguesAndScenarioFixtures() {
        assertThat(provider.providerCode()).isEqualTo("STUB");
        assertThat(provider.fetchLeagues()).hasSize(2);

        var odds = provider.fetchPreMatchOdds(new AsianOddsQueryDto(null, null, null, null));

        assertThat(odds).hasSize(5);
        assertThat(odds)
                .filteredOn(match -> match.lines().isEmpty())
                .extracting(match -> match.providerMatchId())
                .containsExactly("asian-stub-003");
        assertThat(odds)
                .filteredOn(match -> match.homeTeamName().contains("别名"))
                .hasSize(1);
        assertThat(odds)
                .filteredOn(match -> "asian-stub-time-conflict-001".equals(match.providerMatchId()))
                .singleElement()
                .extracting(match -> match.kickoffTime())
                .isEqualTo(OffsetDateTime.parse("2026-07-22T22:30:00+08:00"));
    }

    @Test
    void filtersByKickoffWindowAndBookmaker() {
        AsianOddsQueryDto query = new AsianOddsQueryDto(
                null,
                OffsetDateTime.parse("2026-07-22T19:00:00+08:00"),
                OffsetDateTime.parse("2026-07-22T20:00:00+08:00"),
                "pinnacle"
        );

        var odds = provider.fetchPreMatchOdds(query);

        assertThat(odds).hasSize(2);
        assertThat(odds).allSatisfy(match ->
                assertThat(match.lines()).allSatisfy(line ->
                        assertThat(line.bookmakerCode()).isEqualToIgnoringCase("pinnacle")));
    }

    @Test
    void repeatsIdenticalOddsForSameQuery() {
        AsianOddsQueryDto query = new AsianOddsQueryDto(null, null, null, null);

        assertThat(provider.fetchPreMatchOdds(query))
                .isEqualTo(provider.fetchPreMatchOdds(query));
        assertThat(provider.fetchLeagues())
                .isEqualTo(provider.fetchLeagues());
    }
}
