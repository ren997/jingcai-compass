package com.jingcaicompass.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.home.mapper.HomeSummaryMapper;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.statistics.vo.MarketHitRateVo;
import com.jingcaicompass.statistics.vo.ProbabilityMetricsVo;
import com.jingcaicompass.statistics.vo.RoiMetricsVo;
import com.jingcaicompass.statistics.vo.StatisticsAppliedFilterVo;
import com.jingcaicompass.statistics.vo.StatisticsMetricsVo;
import com.jingcaicompass.statistics.vo.StatisticsSummaryVo;
import com.jingcaicompass.statistics.vo.StatisticsWindowVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
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
class HomeSummaryQueryServiceTest {

    @Mock
    private HomeSummaryMapper homeSummaryMapper;

    @Mock
    private StatisticsQueryService statisticsQueryService;

    @Test
    void aggregatesShanghaiFactsAndPreservesStatisticsWindows() {
        LocalDate today = LocalDate.of(2026, 7, 28);
        Instant generatedAt = Instant.parse("2026-07-28T01:00:00Z");
        StatisticsWindowVo sevenDays = window(LocalDate.of(2026, 7, 22), today);
        StatisticsWindowVo thirtyDays = window(LocalDate.of(2026, 6, 29), today);
        when(homeSummaryMapper.countMatchesByLotteryDate(today)).thenReturn(8L);
        when(homeSummaryMapper.countPublishedPredictionMatchesByLotteryDate(today)).thenReturn(5L);
        when(homeSummaryMapper.countPendingSettlementMatches()).thenReturn(3L);
        when(homeSummaryMapper.countHistoricalPublishedPredictionMatches()).thenReturn(42L);
        when(homeSummaryMapper.selectLatestSportteryCapturedAtByLotteryDate(today))
                .thenReturn(Instant.parse("2026-07-28T00:45:00Z"));
        when(homeSummaryMapper.selectLatestPublishedSnapshotAt())
                .thenReturn(Instant.parse("2026-07-28T00:30:00Z"));
        when(statisticsQueryService.summary(null)).thenReturn(statistics(today, sevenDays, thirtyDays));

        var result = new HomeSummaryQueryServiceImpl(
                homeSummaryMapper,
                statisticsQueryService,
                Clock.fixed(generatedAt, ZoneOffset.UTC)
        ).summary();

        assertThat(result.asOfDate()).isEqualTo(today);
        assertThat(result.today().matchCount()).isEqualTo(8);
        assertThat(result.today().publishedPredictionMatchCount()).isEqualTo(5);
        assertThat(result.pendingSettlementMatchCount()).isEqualTo(3);
        assertThat(result.historicalPublishedMatchCount()).isEqualTo(42);
        assertThat(result.dataFreshness().sportteryLastCapturedAt()).isEqualTo(Instant.parse("2026-07-28T00:45:00Z"));
        assertThat(result.dataFreshness().sportteryDataAgeSeconds()).isEqualTo(900);
        assertThat(result.latestPublishedSnapshotAt()).isEqualTo(Instant.parse("2026-07-28T00:30:00Z"));
        assertThat(result.trailingSevenDays()).isSameAs(sevenDays);
        assertThat(result.trailingThirtyDays()).isSameAs(thirtyDays);
        verify(statisticsQueryService).summary(null);
    }

    @Test
    void keepsFreshnessUnavailableWhenTodayHasNoSportterySnapshot() {
        LocalDate today = LocalDate.of(2026, 7, 28);
        when(statisticsQueryService.summary(null)).thenReturn(statistics(today, window(today, today), window(today, today)));

        var result = new HomeSummaryQueryServiceImpl(
                homeSummaryMapper,
                statisticsQueryService,
                Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC)
        ).summary();

        assertThat(result.dataFreshness().sportteryLastCapturedAt()).isNull();
        assertThat(result.dataFreshness().sportteryDataAgeSeconds()).isNull();
        assertThat(result.latestPublishedSnapshotAt()).isNull();
    }

    @Test
    void unavailableFallbackKeepsUnifiedDataSourceError() {
        assertThatThrownBy(() -> new UnavailableHomeSummaryQueryService().summary())
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE));
    }

    private StatisticsSummaryVo statistics(LocalDate asOfDate, StatisticsWindowVo sevenDays, StatisticsWindowVo thirtyDays) {
        return new StatisticsSummaryVo(
                asOfDate,
                new StatisticsAppliedFilterVo(null, null),
                thirtyDays,
                sevenDays,
                thirtyDays,
                List.of(),
                List.of()
        );
    }

    private StatisticsWindowVo window(LocalDate startDate, LocalDate endDate) {
        StatisticsMetricsVo metrics = new StatisticsMetricsVo(
                0,
                0,
                0,
                0,
                new ProbabilityMetricsVo(0, null, null, List.of()),
                new MarketHitRateVo(MarketTypeEnum.HAD, 0, 0, 0, 0, 0, null),
                new MarketHitRateVo(MarketTypeEnum.HHAD, 0, 0, 0, 0, 0, null),
                new RoiMetricsVo(false, null, null, 0, List.of())
        );
        return new StatisticsWindowVo(startDate, endDate, metrics);
    }
}
