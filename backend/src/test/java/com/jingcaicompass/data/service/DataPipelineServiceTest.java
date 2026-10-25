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
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsSyncService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 单源同步编排：赛程 → 亚盘 → 覆盖率。
 *
 * <p>标准化回填与映射计数两类统计已随映射层删除，本测试只守住两阶段的先后顺序、
 * 失败短路与覆盖率口径。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataPipelineServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 22);

    @Mock
    private SportteryPoolSyncService sportteryPoolSyncService;
    @Mock
    private AsianOddsSyncService asianOddsSyncService;
    @Mock
    private MatchMapper matchMapper;
    @Mock
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    private DataPipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DataPipelineServiceImpl(
                sportteryPoolSyncService,
                asianOddsSyncService,
                matchMapper,
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
        verify(asianOddsSyncService, never()).sync(any());
    }

    @Test
    void partialSportteryContinuesWithOddsInFixedOrder() {
        when(sportteryPoolSyncService.sync(any())).thenReturn(new SportteryPoolSyncResultDto(
                outcome(2L, SyncStatusEnum.PARTIAL, null),
                2,
                1
        ));
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

        var result = service.run(BUSINESS_DATE);

        assertThat(result.status()).isEqualTo(DataPipelineStatusEnum.PARTIAL);
        assertThat(result.asianOddsSnapshotInsertCount()).isEqualTo(1);
        assertThat(result.skippedUnmapped()).isEqualTo(1);
        assertThat(result.coverageRate()).isEqualByComparingTo("0.5000");
        InOrder order = inOrder(sportteryPoolSyncService, asianOddsSyncService);
        order.verify(sportteryPoolSyncService).sync(any());
        order.verify(asianOddsSyncService).sync(any());
    }

    @Test
    void asianExceptionReturnsPartialWithoutRollingBackPriorStages() {
        when(sportteryPoolSyncService.sync(any())).thenReturn(new SportteryPoolSyncResultDto(
                outcome(4L, SyncStatusEnum.SUCCESS, null),
                1,
                1
        ));
        when(asianOddsSyncService.sync(any())).thenThrow(new IllegalStateException("asian unavailable"));
        MatchEntity covered = new MatchEntity();
        covered.setId(300L);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(covered));
        when(asianOddsSnapshotMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        var result = service.run(BUSINESS_DATE);

        assertThat(result.status()).isEqualTo(DataPipelineStatusEnum.PARTIAL);
        assertThat(result.sportterySyncRunId()).isEqualTo(4L);
        assertThat(result.asianOddsStatus()).isEqualTo(SyncStatusEnum.FAILED);
        assertThat(result.errorMessage()).contains("asian unavailable");
        // 赔率阶段失败时覆盖率仍按已落库快照重算，避免把「写了但后面炸了」记成 0
        assertThat(result.coveredMatchCount()).isEqualTo(1);
        assertThat(result.coverageRate()).isEqualByComparingTo("1.0000");
    }

    private static ProviderSyncOutcome outcome(Long id, SyncStatusEnum status, String error) {
        DataSyncRun run = new DataSyncRun();
        run.setId(id);
        run.setSyncStatus(status);
        run.setErrorMessage(error);
        return new ProviderSyncOutcome(run, null, status, false);
    }
}
