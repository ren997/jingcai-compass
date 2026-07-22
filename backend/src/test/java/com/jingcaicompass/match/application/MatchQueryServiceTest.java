package com.jingcaicompass.match.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MatchQueryServiceTest {

    private final MatchQueryService matchQueryService = new MatchQueryService();

    @Test
    void returnsStableDailyStubMatches() {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);

        var matches = matchQueryService.findDailyMatches(lotteryDate);

        assertThat(matches).hasSize(3);
        assertThat(matches).allSatisfy(match -> {
            assertThat(match.lotteryDate()).isEqualTo(lotteryDate);
            assertThat(match.dataSource()).isEqualTo("STUB");
            assertThat(match.lotteryMatchNo()).startsWith("周三");
        });
    }
}
