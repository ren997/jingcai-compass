package com.jingcaicompass.data.service;

import com.jingcaicompass.data.dto.SyncRunFinishDto;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSyncRunServiceTest {

    @Mock
    private DataSyncRunMapper baseMapper;

    private DataSyncRunServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DataSyncRunServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
    }

    @Test
    void startRunCreatesRunningRecordWithZeroCounters() {
        when(baseMapper.insert(any(DataSyncRun.class))).thenAnswer(invocation -> {
            DataSyncRun run = invocation.getArgument(0);
            run.setId(11L);
            return 1;
        });

        DataSyncRun run = service.startRun("STUB", ProviderDataTypeEnum.SPORTTERY_POOL);

        assertThat(run.getId()).isEqualTo(11L);
        assertThat(run.getProviderCode()).isEqualTo("STUB");
        assertThat(run.getDataType()).isEqualTo(ProviderDataTypeEnum.SPORTTERY_POOL);
        assertThat(run.getSyncStatus()).isEqualTo(SyncStatusEnum.RUNNING);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFetchedCount()).isZero();
        assertThat(run.getSuccessCount()).isZero();
        assertThat(run.getFailureCount()).isZero();
        assertThat(run.getRetryCount()).isZero();
        assertThat(run.getQuotaCost()).isZero();
    }

    @Test
    void finishSuccessTransitionsFromRunning() {
        DataSyncRun running = runningRun(21L);
        when(baseMapper.selectById(21L)).thenReturn(running);
        when(baseMapper.updateById(any(DataSyncRun.class))).thenReturn(1);

        DataSyncRun finished = service.finishSuccess(
                21L,
                new SyncRunFinishDto(3, 3, 0, 1, 2, null)
        );

        assertThat(finished.getSyncStatus()).isEqualTo(SyncStatusEnum.SUCCESS);
        assertThat(finished.getFinishedAt()).isNotNull();
        assertThat(finished.getFetchedCount()).isEqualTo(3);
        assertThat(finished.getSuccessCount()).isEqualTo(3);
        assertThat(finished.getRetryCount()).isEqualTo(1);
        assertThat(finished.getQuotaCost()).isEqualTo(2);

        ArgumentCaptor<DataSyncRun> captor = ArgumentCaptor.forClass(DataSyncRun.class);
        verify(baseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSyncStatus()).isEqualTo(SyncStatusEnum.SUCCESS);
    }

    @Test
    void finishPartialAndFailedRequireRunningState() {
        DataSyncRun running = runningRun(22L);
        when(baseMapper.selectById(22L)).thenReturn(running);
        when(baseMapper.updateById(any(DataSyncRun.class))).thenReturn(1);

        assertThat(service.finishPartial(22L, new SyncRunFinishDto(2, 1, 1, 0, 0, "one failed"))
                .getSyncStatus()).isEqualTo(SyncStatusEnum.PARTIAL);

        DataSyncRun alreadyFinished = runningRun(23L);
        alreadyFinished.setSyncStatus(SyncStatusEnum.SUCCESS);
        when(baseMapper.selectById(23L)).thenReturn(alreadyFinished);

        assertThatThrownBy(() -> service.finishFailed(23L, new SyncRunFinishDto(0, 0, 1, 0, 0, "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finished");
    }

    private DataSyncRun runningRun(Long id) {
        DataSyncRun run = new DataSyncRun();
        run.setId(id);
        run.setProviderCode("STUB");
        run.setDataType(ProviderDataTypeEnum.SPORTTERY_POOL);
        run.setSyncStatus(SyncStatusEnum.RUNNING);
        return run;
    }
}
