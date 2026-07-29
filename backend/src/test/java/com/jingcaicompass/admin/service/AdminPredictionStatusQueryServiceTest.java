package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.dto.AdminPredictionLockListQueryDto;
import com.jingcaicompass.admin.dto.AdminPredictionStatusDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSettlementStatusListQueryDto;
import com.jingcaicompass.admin.enums.AdminPredictionLockDiagnosticEnum;
import com.jingcaicompass.admin.enums.AdminSettlementDiagnosticEnum;
import com.jingcaicompass.admin.mapper.AdminPredictionStatusCriteria;
import com.jingcaicompass.admin.mapper.AdminPredictionStatusMapper;
import com.jingcaicompass.history.service.HistoryRecordAssembler;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.HistoryMatchVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.history.vo.SettlementVersionVo;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPredictionStatusQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T01:00:00Z");

    @Mock
    private AdminPredictionStatusMapper statusMapper;
    @Mock
    private HistoryRecordAssembler historyRecordAssembler;

    private AdminPredictionStatusQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminPredictionStatusQueryServiceImpl(
                statusMapper, historyRecordAssembler, new PaginationProperties(50)
        );
        when(statusMapper.selectDatabaseTime()).thenReturn(NOW);
    }

    @Test
    void locksUseDatabaseTimeAndExcludeDraftStatus() {
        when(statusMapper.selectLockPredictionIds(any())).thenReturn(List.of(7L));
        when(statusMapper.countLockPredictions(any())).thenReturn(1L);
        when(statusMapper.countOverdueLocks(any())).thenReturn(1L);
        when(historyRecordAssembler.assemble(List.of(7L))).thenReturn(List.of(item(
                PredictionStatusEnum.PUBLISHED, NOW.minusSeconds(1), List.of(), pendingMarkets()
        )));

        var result = service.locks(new AdminPredictionLockListQueryDto(
                LocalDate.of(2026, 7, 29), " model-v1 ", List.of(PredictionStatusEnum.DRAFT),
                List.of(AdminPredictionLockDiagnosticEnum.OVERDUE), 0, 999
        ));

        ArgumentCaptor<AdminPredictionStatusCriteria> criteria = ArgumentCaptor.forClass(AdminPredictionStatusCriteria.class);
        verify(statusMapper).selectLockPredictionIds(criteria.capture());
        assertThat(criteria.getValue().referenceTime()).isEqualTo(NOW);
        assertThat(criteria.getValue().modelVersion()).isEqualTo("model-v1");
        assertThat(criteria.getValue().predictionStatuses()).containsExactly(PredictionStatusEnum.PUBLISHED, PredictionStatusEnum.LOCKED);
        assertThat(criteria.getValue().pageSize()).isEqualTo(50);
        assertThat(result.manualAttentionCount()).isEqualTo(1);
        assertThat(result.records()).singleElement().satisfies(item ->
                assertThat(item.lockDiagnostics()).extracting(diagnostic -> diagnostic.code()).containsExactly("OVERDUE")
        );
    }

    @Test
    void settlementsDeriveMissingAndStaleMarketsFromCurrentFact() {
        MatchResultFactHistoryVo currentFact = fact(102L, 2, true, MatchResultFactStatusEnum.FINAL);
        MarketSettlementHistoryVo had = new MarketSettlementHistoryVo(MarketTypeEnum.HAD, SettlementStatusEnum.HIT, true, false,
                List.of(new SettlementVersionVo(301L, 1, null, SettlementStatusEnum.HIT, 101L, "t403-v1", true, NOW)));
        when(statusMapper.selectSettlementPredictionIds(any())).thenReturn(List.of(7L));
        when(statusMapper.countSettlementPredictions(any())).thenReturn(1L);
        when(statusMapper.countManualSettlementAttention(any())).thenReturn(1L);
        when(historyRecordAssembler.assemble(List.of(7L))).thenReturn(List.of(item(
                PredictionStatusEnum.LOCKED, NOW.minusSeconds(10), List.of(currentFact),
                List.of(had, pendingMarket(MarketTypeEnum.HHAD))
        )));

        var result = service.settlements(new AdminSettlementStatusListQueryDto(null, null, null, 1, 20));

        assertThat(result.manualAttentionCount()).isEqualTo(1);
        assertThat(result.records()).singleElement().satisfies(item -> {
            assertThat(item.hadSettlement().stale()).isTrue();
            assertThat(item.hhadSettlement().currentStatus()).isEqualTo(SettlementStatusEnum.PENDING);
            assertThat(item.settlementDiagnostics()).extracting(diagnostic -> diagnostic.code())
                    .containsExactly("SETTLEMENT_STALE_HAD", "SETTLEMENT_MISSING_HHAD");
        });
    }

    @Test
    void detailReturnsHistoricalChainsAndRejectsDraftOrMissingPrediction() {
        MatchResultFactHistoryVo historical = fact(101L, 1, false, MatchResultFactStatusEnum.FINAL);
        MatchResultFactHistoryVo current = fact(102L, 2, true, MatchResultFactStatusEnum.FINAL);
        when(statusMapper.selectOperationalPredictionId(7L)).thenReturn(7L);
        when(historyRecordAssembler.assemble(List.of(7L))).thenReturn(List.of(item(
                PredictionStatusEnum.LOCKED, NOW.minusSeconds(10), List.of(historical, current),
                List.of(new MarketSettlementHistoryVo(MarketTypeEnum.HAD, SettlementStatusEnum.MISS, true, false,
                        List.of(new SettlementVersionVo(301L, 1, null, SettlementStatusEnum.HIT, 101L, "t403-v1", false, NOW.minusSeconds(10)),
                                new SettlementVersionVo(302L, 2, 1, SettlementStatusEnum.MISS, 102L, "t403-v1", true, NOW))),
                        pendingMarket(MarketTypeEnum.HHAD))
        )));

        var detail = service.detail(new AdminPredictionStatusDetailQueryDto(7L));

        assertThat(detail.resultFactHistory()).hasSize(2);
        assertThat(detail.resultFactHistory()).filteredOn(fact -> fact.current()).singleElement()
                .extracting(fact -> fact.factId()).isEqualTo(102L);
        assertThat(detail.settlementMarkets()).filteredOn(market -> market.marketType() == MarketTypeEnum.HAD).singleElement()
                .satisfies(market -> assertThat(market.versions()).hasSize(2));

        when(statusMapper.selectOperationalPredictionId(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(new AdminPredictionStatusDetailQueryDto(99L)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.PREDICTION_NOT_FOUND);
    }

    private HistoryListItemVo item(
            PredictionStatusEnum status,
            Instant lockTime,
            List<MatchResultFactHistoryVo> facts,
            List<MarketSettlementHistoryVo> markets
    ) {
        return new HistoryListItemVo(
                7L, 2, "model-v1", "feature-v2", status,
                null, null, null, null, null, null, null, "a".repeat(64),
                NOW.minusSeconds(3600), NOW.minusSeconds(1800), lockTime,
                new HistoryMatchVo(42L, LocalDate.of(2026, 7, 29), "周三042", 1L,
                        "英超", "主队", "客队", NOW.plusSeconds(3600)), facts, markets, false
        );
    }

    private List<MarketSettlementHistoryVo> pendingMarkets() {
        return List.of(pendingMarket(MarketTypeEnum.HAD), pendingMarket(MarketTypeEnum.HHAD));
    }

    private MarketSettlementHistoryVo pendingMarket(MarketTypeEnum marketType) {
        return new MarketSettlementHistoryVo(marketType, SettlementStatusEnum.PENDING, false, false, List.of());
    }

    private MatchResultFactHistoryVo fact(
            Long id,
            int version,
            boolean current,
            MatchResultFactStatusEnum status
    ) {
        return new MatchResultFactHistoryVo(id, version, version == 1 ? null : version - 1, status,
                MatchStatusEnum.FINISHED, 2, 1, NOW, current, NOW);
    }
}
