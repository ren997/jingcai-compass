package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.dto.AdminManualMatchResultDto;
import com.jingcaicompass.data.dto.RawDataPayloadSaveResult;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.service.RawDataPayloadService;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.service.MatchResultFactWriter;
import com.jingcaicompass.settlement.dto.SettlementBatchResultDto;
import com.jingcaicompass.settlement.dto.SettlementRecalculationBatchResultDto;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.system.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 人工赛果补录服务：输入门禁、专用原始载荷和定向结算编排。 */
@ExtendWith(MockitoExtension.class)
class AdminManualMatchResultServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T02:00:00Z");

    @Mock private MatchMapper matchMapper;
    @Mock private MatchResultFactMapper factMapper;
    @Mock private RawDataPayloadService rawDataPayloadService;
    @Mock private MatchResultFactWriter factWriter;
    @Mock private SettlementRecalculationService recalculationService;
    @Mock private SettlementService settlementService;

    private AdminManualMatchResultServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminManualMatchResultServiceImpl(
                matchMapper, factMapper, rawDataPayloadService, factWriter, recalculationService, settlementService,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void storesDedicatedManualEvidenceAndOnlySettlesTargetMatch() {
        MatchEntity match = pastMatch();
        RawDataPayload payload = new RawDataPayload();
        payload.setId(91L);
        MatchResultFact fact = manualFact();
        when(matchMapper.selectById(7L)).thenReturn(match);
        when(factMapper.selectCurrentByMatchId(7L)).thenReturn(null);
        when(rawDataPayloadService.savePayload(any())).thenReturn(new RawDataPayloadSaveResult(payload, false));
        when(factWriter.writeManual(any(), eq(91L))).thenReturn(new MatchResultFactWriter.WriteResult(
                MatchResultFactWriter.WriteOutcome.APPENDED, 101L, 7L, false));
        when(factMapper.selectById(101L)).thenReturn(fact);
        when(recalculationService.recalculateOutdatedSettlementsForMatch(7L))
                .thenReturn(new SettlementRecalculationBatchResultDto(1, 1, 2, 0, 0, 0));
        when(settlementService.settlePendingPredictionsForMatch(7L))
                .thenReturn(new SettlementBatchResultDto(2, 1, 2, 1, 0, 0));

        var result = service.record(finalRequest(), "admin-1");

        assertThat(result.resultSource()).isEqualTo(MatchResultFactSourceEnum.MANUAL);
        assertThat(result.settlementTriggered()).isTrue();
        assertThat(result.settlement().recalculatedMarketCount()).isEqualTo(2);
        assertThat(result.settlement().settledMarketCount()).isEqualTo(2);
        verify(rawDataPayloadService).markParseSuccess(91L);
        verify(recalculationService).recalculateOutdatedSettlementsForMatch(7L);
        verify(settlementService).settlePendingPredictionsForMatch(7L);
    }

    @Test
    void identicalManualInputIsIdempotentAndDoesNotRepeatSettlement() {
        MatchEntity match = pastMatch();
        RawDataPayload payload = new RawDataPayload();
        payload.setId(91L);
        when(matchMapper.selectById(7L)).thenReturn(match);
        when(factMapper.selectCurrentByMatchId(7L)).thenReturn(null);
        when(rawDataPayloadService.savePayload(any())).thenReturn(new RawDataPayloadSaveResult(payload, true));
        when(factWriter.writeManual(any(), eq(91L))).thenReturn(new MatchResultFactWriter.WriteResult(
                MatchResultFactWriter.WriteOutcome.UNCHANGED, 101L, 7L, false));
        when(factMapper.selectById(101L)).thenReturn(manualFact());

        var result = service.record(finalRequest(), "admin-1");

        assertThat(result.settlementTriggered()).isFalse();
        verify(rawDataPayloadService, never()).markParseSuccess(any());
        verify(recalculationService, never()).recalculateOutdatedSettlementsForMatch(any());
        verify(settlementService, never()).settlePendingPredictionsForMatch(any());
    }

    @Test
    void rejectsFutureMatchAndUnsupportedPendingStatusBeforeSavingEvidence() {
        MatchEntity future = pastMatch();
        future.setKickoffTime(NOW.plusSeconds(1));
        when(matchMapper.selectById(7L)).thenReturn(future);

        assertThatThrownBy(() -> service.record(finalRequest(), "admin-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未开赛");
        verify(rawDataPayloadService, never()).savePayload(any());

        assertThatThrownBy(() -> service.record(new AdminManualMatchResultDto(
                7L, MatchResultFactStatusEnum.PENDING, MatchStatusEnum.SCHEDULED, null, null,
                "人工说明", "测试", true), "admin-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅支持");
    }

    @Test
    void rejectsInvalidScoreVoidStateAndOversizedEvidenceBeforeSavingEvidence() {
        assertThatThrownBy(() -> service.record(new AdminManualMatchResultDto(
                7L, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, -1, 1,
                "人工说明", "测试", true), "admin-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非负比分");
        assertThatThrownBy(() -> service.record(new AdminManualMatchResultDto(
                7L, MatchResultFactStatusEnum.VOID, MatchStatusEnum.FINISHED, null, null,
                "人工说明", "测试", true), "admin-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅允许取消或中止");
        assertThatThrownBy(() -> service.record(new AdminManualMatchResultDto(
                7L, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, 1, 0,
                "a".repeat(501), "测试", true), "admin-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("来源说明和补录原因");
        verify(rawDataPayloadService, never()).savePayload(any());
    }

    private MatchEntity pastMatch() {
        MatchEntity match = new MatchEntity();
        match.setId(7L);
        match.setKickoffTime(NOW.minusSeconds(60));
        return match;
    }

    private MatchResultFact manualFact() {
        MatchResultFact fact = new MatchResultFact();
        fact.setId(101L);
        fact.setFactVersion(1);
        fact.setResultSource(MatchResultFactSourceEnum.MANUAL);
        fact.setFactStatus(MatchResultFactStatusEnum.FINAL);
        fact.setMatchStatus(MatchStatusEnum.FINISHED);
        fact.setHomeScore(2);
        fact.setAwayScore(1);
        fact.setSourceNote("人工说明");
        fact.setEntryReason("测试");
        fact.setEnteredBy("admin-1");
        fact.setProviderUpdatedAt(NOW);
        return fact;
    }

    private AdminManualMatchResultDto finalRequest() {
        return new AdminManualMatchResultDto(7L, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED,
                2, 1, "人工说明", "测试", true);
    }
}
