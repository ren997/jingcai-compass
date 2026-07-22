package com.jingcaicompass.match.infrastructure.sporttery;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StubSportteryProviderTest {

    private final StubSportteryProvider provider = new StubSportteryProvider();

    @Test
    void returnsClearlyLabelledSyntheticMatches() {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);

        var matches = provider.findDailyMatches(lotteryDate);

        assertThat(provider.providerCode()).isEqualTo("STUB");
        assertThat(matches).hasSize(3);
        assertThat(matches).allSatisfy(match -> {
            assertThat(match.lotteryDate()).isEqualTo(lotteryDate);
            assertThat(match.leagueName()).isEqualTo("演示联赛");
            assertThat(match.homeTeamName()).startsWith("演示主队");
        });
    }
}
