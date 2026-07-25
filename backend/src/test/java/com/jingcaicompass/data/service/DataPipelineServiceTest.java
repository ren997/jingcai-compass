package com.jingcaicompass.data.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.DataPipelineStatusEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.odds.service.AsianOddsSyncService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataPipelineServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 22);

    @Mock
    private SportteryPoolSyncService sportteryPoolSyncService;
    @Mock
    private MatchNormalizationBackfillService normalizationBackfillService;
    @Mock
    private AsianOddsSyncService asianOddsSyncService;
    @Mock
    private AsianOddsProvider asianOddsProvider;
    @Mock
    private MatchMapper matchMapper;
    @Mock
    private MatchSourceMappingMapper matchSourceMappingMapper;
    @Mock
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    private DataPipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DataPipelineServiceImpl(
                sportteryPoolSyncService,
                normalizationBackfillService,
                asianOddsSyncService,
                asianOddsProvider,
                matchMapper,
                matchSourceMappingMapper,
                asianOddsSnapshotMapper
        );
    }

    @Test
    void sportteryFailureStopsFollowingStages() {
        when(sportteryPoolSyncService.sync(any())).thenReturn(new SportteryPoolSyncResultDto(
                outcome(1L, SyncStatusEnum.FAILED, "sporttery failed"),
                0,
                0
        ));

        var result = service.run(BUSINESS_DATE);

        assertThat(result.status()).isEqualTo(DataPipelineStatusEnum.FAILED);
        assertThat(result.sportterySyncRunId()).isEqualTo(1L);
        assertThat(result.errorMessage()).contains("sporttery failed");
        verify(normalizationBackfillService, never()).backfill(any());
        verify(asianOddsSyncService, never()).sync(any());
    }

    @Test
    void partialSportteryContinuesInFixedOrder() {
        NormalizationBackfillResultDto normalization = new NormalizationBackfillResultDto(
                BUSINESS_DATE,
                2,
                1,
                1,
                0,
                1,
                Map.of(),
                List.of()
        );
        when(sportteryPoolSyncService.sync(any())).thenReturn(new SportteryPoolSyncResultDto(
                outcome(2L, SyncStatusEnum.PARTIAL, null),
                2,
                1
        ));
        when(normalizationBackfillService.backfill(BUSINESS_DATE)).thenReturn(normalization);
        when(asianOddsSyncService.sync(any())).thenReturn(new AsianOddsSyncResultDto(
                outcome(3L, SyncStatusEnum.SUCCESS, null),
                false,
                1,
                1,
                0,
                0,
                2,
                1,
                new BigDecimal("0.5000"),
                1
        ));
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var result = service.run(BUSINESS_DATE);

        assertThat(result.status()).isEqualTo(DataPipelineStatusEnum.PARTIAL);
        assertThat(result.normalization()).isSameAs(normalization);
        assertThat(result.coverageRate()).isEqualByComparingTo("0.5000");
        InOrder order = inOrder(
                sportteryPoolSyncService,
                normalizationBackfillService,
                asianOddsSyncService
        );
        order.verify(sportteryPoolSyncService).sync(any());
        order.verify(normalizationBackfillService).backfill(BUSINESS_DATE);
        order.verify(asianOddsSyncService).sync(any());
    }

    @Test
    void asianExceptionReturnsPartialWithoutRollingBackPriorStages() {
        when(sportteryPoolSyncService.sync(any())).thenReturn(new SportteryPoolSyncResultDto(
                outcome(4L, SyncStatusEnum.SUCCESS, null),
                1,
                1
        ));
        when(normalizationBackfillService.backfill(BUSINESS_DATE))
                .thenReturn(NormalizationBackfillResultDto.empty(BUSINESS_DATE));
        when(asianOddsSyncService.sync(any())).thenThrow(new IllegalStateException("asian unavailable"));
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var result = service.run(BUSINESS_DATE);

        assertThat(result.status()).isEqualTo(DataPipelineStatusEnum.PARTIAL);
        assertThat(result.sportterySyncRunId()).isEqualTo(4L);
        assertThat(result.asianOddsStatus()).isEqualTo(SyncStatusEnum.FAILED);
        assertThat(result.errorMessage()).contains("asian unavailable");
    }

    private static ProviderSyncOutcome outcome(Long id, SyncStatusEnum status, String error) {
        DataSyncRun run = new DataSyncRun();
        run.setId(id);
        run.setSyncStatus(status);
        run.setErrorMessage(error);
        return new ProviderSyncOutcome(run, null, status, false);
    }
}
