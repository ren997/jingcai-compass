package com.jingcaicompass.statistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.service.HistoryRecordAssembler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatisticsQueryServiceTest {

    @Mock
    private HistoryQueryMapper historyQueryMapper;

    @Mock
    private HistoryRecordAssembler historyRecordAssembler;

    @Test
    void defaultsRequestedAndTrailingThirtyDayWindowsToShanghaiBusinessDate() {
        when(historyQueryMapper.selectLockedPredictionIds(any())).thenReturn(List.of());
        when(historyRecordAssembler.assemble(any())).thenReturn(List.of());
        StatisticsQueryService service = new StatisticsQueryServiceImpl(
                historyQueryMapper,
                historyRecordAssembler,
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.summary(null);

        assertThat(result.asOfDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(result.requestedWindow().startDate()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(result.requestedWindow().endDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(result.trailingSevenDays().startDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(result.trailingThirtyDays().startDate()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(result.requestedWindow().metrics().probabilityMetrics().sampleSize()).isZero();
    }
}
