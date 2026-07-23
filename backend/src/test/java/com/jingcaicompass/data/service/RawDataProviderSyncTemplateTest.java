package com.jingcaicompass.data.service;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.dto.RawDataPayloadSaveDto;
import com.jingcaicompass.data.dto.RawDataPayloadSaveResult;
import com.jingcaicompass.data.dto.SyncRunFinishDto;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawDataProviderSyncTemplateTest {

    @Mock
    private DataSyncRunService dataSyncRunService;

    @Mock
    private RawDataPayloadService rawDataPayloadService;

    private ProviderSyncTemplate template;

    @BeforeEach
    void setUp() {
        template = new ProviderSyncTemplate(dataSyncRunService, rawDataPayloadService);
    }

    @Test
    void executesFixedOrderAndFinishesSuccess() {
        DataSyncRun running = run(1L, SyncStatusEnum.RUNNING);
        DataSyncRun success = run(1L, SyncStatusEnum.SUCCESS);
        RawDataPayload payload = payload(10L);
        when(dataSyncRunService.startRun("STUB", ProviderDataTypeEnum.SPORTTERY_POOL)).thenReturn(running);
        when(rawDataPayloadService.savePayload(any(RawDataPayloadSaveDto.class)))
                .thenReturn(new RawDataPayloadSaveResult(payload, false));
        when(rawDataPayloadService.markParseSuccess(10L)).thenReturn(payload);
        when(dataSyncRunService.finishSuccess(eq(1L), any(SyncRunFinishDto.class))).thenReturn(success);

        ProviderSyncOutcome outcome = template.execute(
                "STUB",
                ProviderDataTypeEnum.SPORTTERY_POOL,
                () -> new ProviderFetchResult("2026-07-22", "{\"ok\":true}", 200, Instant.now(), 0, 1),
                (dataType, requestKey, saved) -> new ProviderParseResult(3, 0, null)
        );

        assertThat(outcome.status()).isEqualTo(SyncStatusEnum.SUCCESS);
        assertThat(outcome.duplicatePayload()).isFalse();
        InOrder order = inOrder(dataSyncRunService, rawDataPayloadService);
        order.verify(dataSyncRunService).startRun("STUB", ProviderDataTypeEnum.SPORTTERY_POOL);
        order.verify(rawDataPayloadService).savePayload(any(RawDataPayloadSaveDto.class));
        order.verify(rawDataPayloadService).markParseSuccess(10L);
        order.verify(dataSyncRunService).finishSuccess(eq(1L), any(SyncRunFinishDto.class));
    }

    @Test
    void keepsRawWhenSingleItemFailsAndMarksPartial() {
        DataSyncRun running = run(2L, SyncStatusEnum.RUNNING);
        DataSyncRun partial = run(2L, SyncStatusEnum.PARTIAL);
        RawDataPayload payload = payload(20L);
        when(dataSyncRunService.startRun("STUB", ProviderDataTypeEnum.SPORTTERY_POOL)).thenReturn(running);
        when(rawDataPayloadService.savePayload(any())).thenReturn(new RawDataPayloadSaveResult(payload, false));
        when(rawDataPayloadService.markParseFailed(eq(20L), any())).thenReturn(payload);
        when(dataSyncRunService.finishPartial(eq(2L), any())).thenReturn(partial);

        ProviderSyncOutcome outcome = template.execute(
                "STUB",
                ProviderDataTypeEnum.SPORTTERY_POOL,
                () -> new ProviderFetchResult("2026-07-22", "{\"ok\":true}", 200, null, 0, 0),
                (dataType, requestKey, saved) -> new ProviderParseResult(2, 1, "row-3 failed")
        );

        assertThat(outcome.status()).isEqualTo(SyncStatusEnum.PARTIAL);
        assertThat(outcome.payload()).isSameAs(payload);
        verify(rawDataPayloadService).markParseFailed(20L, "row-3 failed");
        verify(dataSyncRunService, never()).finishSuccess(any(), any());
    }

    @Test
    void fetchFailureFinishesFailedWithoutSaveOrParse() {
        DataSyncRun running = run(3L, SyncStatusEnum.RUNNING);
        DataSyncRun failed = run(3L, SyncStatusEnum.FAILED);
        when(dataSyncRunService.startRun("STUB", ProviderDataTypeEnum.ASIAN_ODDS)).thenReturn(running);
        when(dataSyncRunService.finishFailed(eq(3L), any())).thenReturn(failed);

        ProviderSyncOutcome outcome = template.execute(
                "STUB",
                ProviderDataTypeEnum.ASIAN_ODDS,
                () -> {
                    throw new IllegalStateException("upstream down");
                },
                (dataType, requestKey, saved) -> new ProviderParseResult(1, 0, null)
        );

        assertThat(outcome.status()).isEqualTo(SyncStatusEnum.FAILED);
        assertThat(outcome.payload()).isNull();
        verify(rawDataPayloadService, never()).savePayload(any());
        verify(rawDataPayloadService, never()).markParseSuccess(any());
    }

    @Test
    void parseExceptionMarksFailedButKeepsSavedPayload() {
        DataSyncRun running = run(4L, SyncStatusEnum.RUNNING);
        DataSyncRun failed = run(4L, SyncStatusEnum.FAILED);
        RawDataPayload payload = payload(40L);
        when(dataSyncRunService.startRun("STUB", ProviderDataTypeEnum.SPORTTERY_POOL)).thenReturn(running);
        when(rawDataPayloadService.savePayload(any())).thenReturn(new RawDataPayloadSaveResult(payload, false));
        when(rawDataPayloadService.markParseFailed(eq(40L), any())).thenReturn(payload);
        when(dataSyncRunService.finishFailed(eq(4L), any())).thenReturn(failed);

        ProviderSyncOutcome outcome = template.execute(
                "STUB",
                ProviderDataTypeEnum.SPORTTERY_POOL,
                () -> new ProviderFetchResult("2026-07-22", "{\"ok\":true}", 200, null, 1, 2),
                (dataType, requestKey, saved) -> {
                    throw new IllegalStateException("boom");
                }
        );

        assertThat(outcome.status()).isEqualTo(SyncStatusEnum.FAILED);
        assertThat(outcome.payload()).isSameAs(payload);
        verify(rawDataPayloadService).savePayload(any());
        verify(rawDataPayloadService).markParseFailed(40L, "boom");
    }

    @Test
    void duplicatePayloadStillCompletesAndReportsFlag() {
        DataSyncRun running = run(5L, SyncStatusEnum.RUNNING);
        DataSyncRun success = run(5L, SyncStatusEnum.SUCCESS);
        RawDataPayload payload = payload(50L);
        when(dataSyncRunService.startRun("STUB", ProviderDataTypeEnum.SPORTTERY_POOL)).thenReturn(running);
        when(rawDataPayloadService.savePayload(any())).thenReturn(new RawDataPayloadSaveResult(payload, true));
        when(rawDataPayloadService.markParseSuccess(50L)).thenReturn(payload);
        when(dataSyncRunService.finishSuccess(eq(5L), any())).thenReturn(success);

        ProviderSyncOutcome outcome = template.execute(
                "STUB",
                ProviderDataTypeEnum.SPORTTERY_POOL,
                () -> new ProviderFetchResult("2026-07-22", "{\"ok\":true}", 200, null, 0, 0),
                (dataType, requestKey, saved) -> new ProviderParseResult(1, 0, null)
        );

        assertThat(outcome.duplicatePayload()).isTrue();
        assertThat(outcome.status()).isEqualTo(SyncStatusEnum.SUCCESS);
    }

    private DataSyncRun run(Long id, SyncStatusEnum status) {
        DataSyncRun run = new DataSyncRun();
        run.setId(id);
        run.setSyncStatus(status);
        run.setProviderCode("STUB");
        run.setDataType(ProviderDataTypeEnum.SPORTTERY_POOL);
        return run;
    }

    private RawDataPayload payload(Long id) {
        RawDataPayload payload = new RawDataPayload();
        payload.setId(id);
        return payload;
    }
}
