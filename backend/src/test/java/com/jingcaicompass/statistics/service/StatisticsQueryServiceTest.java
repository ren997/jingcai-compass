package com.jingcaicompass.statistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.service.HistoryRecordAssembler;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.statistics.dto.StatisticsQueryCriteriaDto;
import com.jingcaicompass.statistics.dto.StatisticsSummaryQueryDto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
        // 未指定产品线时默认体彩，保持既有 API 行为。
        assertThat(result.appliedFilter().predictionType()).isEqualTo(PredictionTypeEnum.SPORTTERY);
    }

    @Test
    void passesPredictionTypeThroughEveryCriteriaSoWindowsCannotMixProductLines() {
        List<StatisticsQueryCriteriaDto> captured = new ArrayList<>();
        when(historyQueryMapper.selectLockedPredictionIds(any())).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return List.of();
        });
        when(historyRecordAssembler.assemble(any())).thenReturn(List.of());
        StatisticsQueryService service = new StatisticsQueryServiceImpl(
                historyQueryMapper,
                historyRecordAssembler,
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.summary(
                new StatisticsSummaryQueryDto(null, null, null, null, PredictionTypeEnum.ASIAN));

        // 请求窗口 + 近 7 天 + 近 30 天共三次查询，每一次都必须带同一产品线。
        assertThat(captured).hasSize(3);
        assertThat(captured).allSatisfy(criteria ->
                assertThat(criteria.predictionType()).isEqualTo(PredictionTypeEnum.ASIAN));
        assertThat(result.appliedFilter().predictionType()).isEqualTo(PredictionTypeEnum.ASIAN);
        assertThat(result.requestedWindow().metrics().markets()).hasSize(2);
        assertThat(result.requestedWindow().metrics().markets().get(0).marketType().isAsian()).isTrue();
    }
}
