package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.dto.AdminSportteryPoolBackfillDto;
import com.jingcaicompass.admin.vo.AdminSportteryPoolBackfillVo;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 赛程回填的窗口校验与逐日容错。
 *
 * <p>这两类行为都只在管理端手工触发时才走到，集成测试不会覆盖：窗口上限写错会让
 * 浏览器先超时、服务端继续扣额度；「一天失败就整体中断」会让补一个季度退化成
 * 「每天点一次、坏一天全停」。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminSportteryPoolBackfillServiceImplTest {

    @Mock
    private SportteryPoolSyncService sportteryPoolSyncService;

    @Mock
    private ObjectProvider<SportteryPoolSyncService> syncServiceProvider;

    private AdminSportteryPoolBackfillServiceImpl service;

    @BeforeEach
    void setUp() {
        // lenient：窗口校验类用例根本走不到取依赖那一步，严格模式会判成多余 stub。
        lenient().when(syncServiceProvider.getIfAvailable()).thenReturn(sportteryPoolSyncService);
        service = new AdminSportteryPoolBackfillServiceImpl(syncServiceProvider);
    }

    @Test
    void reportsDataSourceUnavailableWhenNoSyncService() {
        when(syncServiceProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.backfill(new AdminSportteryPoolBackfillDto(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
    }

    @Test
    void rejectsWindowLongerThanSevenDays() {
        assertThatThrownBy(() -> service.backfill(new AdminSportteryPoolBackfillDto(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多连续 7 天");
        verify(sportteryPoolSyncService, never()).sync(any());
    }

    @Test
    void allowsExactlySevenDays() {
        stubSuccess(1);
        AdminSportteryPoolBackfillVo vo = service.backfill(new AdminSportteryPoolBackfillDto(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7)));
        assertThat(vo.dayCount()).isEqualTo(7);
        assertThat(vo.successDays()).isEqualTo(7);
        verify(sportteryPoolSyncService, times(7)).sync(any(SportteryPoolSyncRequestDto.class));
    }

    @Test
    void rejectsInvertedOrMissingRange() {
        assertThatThrownBy(() -> service.backfill(new AdminSportteryPoolBackfillDto(
                LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得晚于");

        assertThatThrownBy(() -> service.backfill(new AdminSportteryPoolBackfillDto(
                LocalDate.of(2026, 7, 1), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须同时提供");
    }

    @Test
    void accumulatesUpsertsAndQuotaAcrossDays() {
        stubSuccess(3);
        AdminSportteryPoolBackfillVo vo = service.backfill(new AdminSportteryPoolBackfillDto(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3)));

        assertThat(vo.matchUpsertCount()).isEqualTo(9);
        assertThat(vo.snapshotInsertCount()).isEqualTo(9);
        // 每天一次赛程调用 = 一个额度单位，三天就是三。
        assertThat(vo.quotaCost()).isEqualTo(3);
        assertThat(vo.failedDays()).isZero();
        assertThat(vo.errorSummary()).isNull();
    }

    @Test
    void keepsGoingAfterOneDayFails() {
        when(sportteryPoolSyncService.sync(any(SportteryPoolSyncRequestDto.class)))
                .thenAnswer(invocation -> {
                    SportteryPoolSyncRequestDto request = invocation.getArgument(0);
                    if (LocalDate.of(2026, 7, 2).equals(request.businessDate())) {
                        throw new IllegalStateException("上游 500");
                    }
                    return successResult(2);
                });

        AdminSportteryPoolBackfillVo vo = service.backfill(new AdminSportteryPoolBackfillDto(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3)));

        // 关键断言：第三天仍然被调用，不是「第二天挂了后面就不跑」。
        verify(sportteryPoolSyncService, times(3)).sync(any(SportteryPoolSyncRequestDto.class));
        assertThat(vo.successDays()).isEqualTo(2);
        assertThat(vo.failedDays()).isEqualTo(1);
        assertThat(vo.matchUpsertCount()).isEqualTo(4);
        // 失败日不贡献额度：上游没成功响应，就不该把调用单位算进去。
        assertThat(vo.quotaCost()).isEqualTo(2);
        assertThat(vo.days()).hasSize(3);
        assertThat(vo.days().get(1).success()).isFalse();
        assertThat(vo.days().get(1).errorSummary()).isEqualTo("上游 500");
        assertThat(vo.errorSummary()).contains("1 天失败");
    }

    private void stubSuccess(int matchCount) {
        when(sportteryPoolSyncService.sync(any(SportteryPoolSyncRequestDto.class)))
                .thenAnswer(invocation -> successResult(matchCount));
    }

    private SportteryPoolSyncResultDto successResult(int matchCount) {
        DataSyncRun run = new DataSyncRun();
        run.setSyncStatus(SyncStatusEnum.SUCCESS);
        run.setQuotaCost(1);
        ProviderSyncOutcome outcome = new ProviderSyncOutcome(run, null, SyncStatusEnum.SUCCESS, false);
        return new SportteryPoolSyncResultDto(outcome, matchCount, matchCount);
    }
}
