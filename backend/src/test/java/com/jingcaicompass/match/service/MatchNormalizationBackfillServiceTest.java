package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.NormalizationPendingReasonEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchNormalizationBackfillServiceTest {

    @Mock
    private MatchMapper matchMapper;
    @Mock
    private MatchNormalizationWorker worker;
    @Mock
    private SportteryProvider sportteryProvider;

    @Test
    void continuesAfterSingleMatchFailureAndAggregatesPendingReasons() {
        LocalDate businessDate = LocalDate.of(2026, 7, 22);
        MatchEntity first = match(1L, "周三001");
        MatchEntity second = match(2L, "周三002");
        MatchEntity third = match(3L, "周三003");
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second, third));
        when(sportteryProvider.providerCode()).thenReturn("STUB");
        when(worker.normalize(1L, "STUB")).thenReturn(
                new MatchNormalizationWorker.ItemResult(1L, true, true, Set.of())
        );
        when(worker.normalize(2L, "STUB")).thenReturn(new MatchNormalizationWorker.ItemResult(
                2L,
                false,
                false,
                Set.of(
                        NormalizationPendingReasonEnum.HOME_TEAM_PENDING,
                        NormalizationPendingReasonEnum.AWAY_TEAM_PENDING
                )
        ));
        when(worker.normalize(3L, "STUB")).thenThrow(new IllegalStateException("bad match"));

        var service = new MatchNormalizationBackfillServiceImpl(
                matchMapper,
                worker,
                sportteryProvider
        );
        var result = service.backfill(businessDate);

        assertThat(result.totalMatchCount()).isEqualTo(3);
        assertThat(result.normalizedMatchCount()).isEqualTo(1);
        assertThat(result.pendingMatchCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.updatedMatchCount()).isEqualTo(1);
        assertThat(result.pendingReasonCounts())
                .containsEntry(NormalizationPendingReasonEnum.HOME_TEAM_PENDING, 1)
                .containsEntry(NormalizationPendingReasonEnum.AWAY_TEAM_PENDING, 1);
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.message()).contains("bad match"));
    }

    private static MatchEntity match(Long id, String lotteryMatchNo) {
        MatchEntity match = new MatchEntity();
        match.setId(id);
        match.setLotteryMatchNo(lotteryMatchNo);
        return match;
    }
}
