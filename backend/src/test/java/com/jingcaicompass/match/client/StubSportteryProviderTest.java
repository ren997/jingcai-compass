package com.jingcaicompass.match.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.dto.SportteryMatchResultPayloadDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubSportteryProviderTest {

    private final StubSportteryProvider provider = new StubSportteryProvider();

    @Test
    void returnsClearlyLabelledSyntheticMatchesIncludingEdgeStatuses() {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);

        var matches = provider.findDailyMatches(lotteryDate);

        assertThat(provider.providerCode()).isEqualTo("STUB");
        assertThat(matches).hasSize(5);
        assertThat(matches).allSatisfy(match -> {
            assertThat(match.lotteryDate()).isEqualTo(lotteryDate);
            assertThat(match.leagueName()).isEqualTo("演示联赛");
            assertThat(match.homeTeamName()).startsWith("演示主队");
        });
        assertThat(matches)
                .filteredOn(match -> match.matchStatus() == MatchStatusEnum.POSTPONED)
                .hasSize(1);
        assertThat(matches)
                .filteredOn(match -> match.matchStatus() == MatchStatusEnum.CANCELLED)
                .hasSize(1);
    }

    @Test
    void returnsStableRawResultsIncludingAmendedPostponedAndCancelled() throws Exception {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);

        var first = provider.fetchMatchResultsRaw(lotteryDate, lotteryDate);
        var second = provider.fetchMatchResultsRaw(lotteryDate, lotteryDate);
        List<SportteryMatchResultDto> results = new ObjectMapper()
                .findAndRegisterModules()
                .readValue(first.payloadJson(), SportteryMatchResultPayloadDto.class)
                .results();

        assertThat(first).isEqualTo(second);
        assertThat(first.requestKey()).isEqualTo("2026-07-22:2026-07-22");
        assertThat(results)
                .filteredOn(result -> result.matchStatus() == MatchStatusEnum.FINISHED && !result.amended())
                .hasSize(2);
        assertThat(results)
                .filteredOn(result -> result.amended())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.matchId()).isEqualTo("stub-2026-07-22-001");
                    assertThat(result.homeScore()).isEqualTo(1);
                    assertThat(result.awayScore()).isEqualTo(1);
                });
        assertThat(results)
                .filteredOn(result -> result.matchStatus() == MatchStatusEnum.POSTPONED)
                .hasSize(1);
        assertThat(results)
                .filteredOn(result -> result.matchStatus() == MatchStatusEnum.CANCELLED)
                .hasSize(1);
    }

    @Test
    void repeatsSameDailyPoolForIdenticalInput() {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);

        assertThat(provider.findDailyMatches(lotteryDate))
                .isEqualTo(provider.findDailyMatches(lotteryDate));
    }
}
