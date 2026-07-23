package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchQueryServiceTest {

    @Test
    void mapsProviderMatchesToPublicView() {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);
        SportteryProvider provider = mock(SportteryProvider.class);
        when(provider.providerCode()).thenReturn("TEST_PROVIDER");
        when(provider.findDailyMatches(lotteryDate)).thenReturn(List.of(
                new SportteryMatchDto(
                        "provider-1",
                        lotteryDate,
                        "周三201",
                        "韩职",
                        "首尔FC",
                        "浦项制铁",
                        OffsetDateTime.parse("2026-07-22T18:30:00+08:00"),
                        -1,
                        MatchStatusEnum.SCHEDULED
                )
        ));
        MatchQueryService matchQueryService = new MatchQueryServiceImpl(provider);

        var matches = matchQueryService.findDailyMatches(lotteryDate);

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.matchId()).isEqualTo("provider-1");
            assertThat(match.lotteryDate()).isEqualTo(lotteryDate);
            assertThat(match.officialHandicap()).isEqualTo(-1);
            assertThat(match.dataSource()).isEqualTo("TEST_PROVIDER");
        });
    }
}
