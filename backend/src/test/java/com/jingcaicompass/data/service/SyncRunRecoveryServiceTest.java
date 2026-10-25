package com.jingcaicompass.data.service;

import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动恢复：把上个进程遗留的 RUNNING 记录收尾。
 *
 * <p>这层存在的理由不是「清理脏数据」，而是管理端没有心跳列，无法自证一条 RUNNING 是
 * 真的在跑还是进程已经死了。因此断言重点是<b>阈值</b>：太宽松会误伤正在执行的任务，
 * 太严苛则孤儿记录永远清不掉。</p>
 */
@ExtendWith(MockitoExtension.class)
class SyncRunRecoveryServiceTest {

    @Mock
    private DataSyncRunMapper dataSyncRunMapper;

    @Test
    void failsRunningRunsOlderThanStaleThreshold() {
        SyncRunRecoveryService service = new SyncRunRecoveryService(dataSyncRunMapper);
        Instant now = Instant.parse("2026-10-25T11:20:23Z");
        when(dataSyncRunMapper.failStaleRunning(any(), anyString(), any())).thenReturn(5);

        int recovered = service.recoverStaleRuns(now);

        assertThat(recovered).isEqualTo(5);
        ArgumentCaptor<Instant> finishedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(dataSyncRunMapper).failStaleRunning(finishedAt.capture(), reason.capture(), threshold.capture());
        assertThat(finishedAt.getValue()).isEqualTo(now);
        // 阈值必须严格等于「现在 - 10 分钟」：本轮同步实测 4~7 分钟，卡在 10 分钟以内的行
        // 只可能是另一个实例正在写，不能动。
        assertThat(threshold.getValue()).isEqualTo(now.minus(SyncRunRecoveryService.STALE_AFTER));
        assertThat(reason.getValue()).contains("STALE_RUN_RECOVERED");
    }

    @Test
    void reportsZeroWhenNoStaleRunExists() {
        SyncRunRecoveryService service = new SyncRunRecoveryService(dataSyncRunMapper);
        when(dataSyncRunMapper.failStaleRunning(any(), anyString(), any())).thenReturn(0);

        assertThat(service.recoverStaleRuns(Instant.parse("2026-10-25T11:20:23Z"))).isZero();
    }
}
