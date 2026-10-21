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
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
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
            assertThat(item.predictionType()).isEqualTo(PredictionTypeEnum.SPORTTERY);
            assertThat(item.settlementMarkets()).extracting(market -> market.marketType())
                    .containsExactly(MarketTypeEnum.HAD, MarketTypeEnum.HHAD);
            assertThat(item.settlementMarkets().get(0).stale()).isTrue();
            assertThat(item.settlementMarkets().get(1).currentStatus()).isEqualTo(SettlementStatusEnum.PENDING);
            assertThat(item.settlementDiagnostics()).extracting(diagnostic -> diagnostic.code())
                    .containsExactly("SETTLEMENT_STALE", "SETTLEMENT_MISSING");
            // 诊断码不再按市场硬编码，市场由 marketType 承载。
            assertThat(item.settlementDiagnostics()).extracting(diagnostic -> diagnostic.marketType())
                    .containsExactly(MarketTypeEnum.HAD, MarketTypeEnum.HHAD);
        });
    }

    /**
     * 亚盘预测必须按自己的市场集合展示，不能被判成「HAD 待结算」。
     *
     * <p>这是本次修复的核心回归：此前 {@code toItem} 固定 {@code markets.get(HAD)}/{@code get(HHAD)}，
     * 亚盘预测两次都取到 null，已落库的结算退化为 PENDING 并报两条缺失诊断。
     */
    @Test
    void asianPredictionsExposeTheirOwnMarketsAndNeverReportMissingHad() {
        MatchResultFactHistoryVo currentFact = fact(102L, 2, true, MatchResultFactStatusEnum.FINAL);
        MarketSettlementHistoryVo settledHandicap = new MarketSettlementHistoryVo(
                MarketTypeEnum.ASIAN_HANDICAP, SettlementStatusEnum.HIT, true, false,
                List.of(new SettlementVersionVo(
                        401L, 1, null, SettlementStatusEnum.HIT, 102L, "t613-v1", true, NOW, new BigDecimal("0.9200"))));
        when(statusMapper.selectSettlementPredictionIds(any())).thenReturn(List.of(8L));
        when(statusMapper.countSettlementPredictions(any())).thenReturn(1L);
        when(statusMapper.countManualSettlementAttention(any())).thenReturn(1L);
        when(historyRecordAssembler.assemble(List.of(8L))).thenReturn(List.of(asianItem(
                currentFact, List.of(settledHandicap, pendingMarket(MarketTypeEnum.ASIAN_TOTAL_GOALS))
        )));

        var result = service.settlements(new AdminSettlementStatusListQueryDto(null, null, null, 1, 20));

        assertThat(result.records()).singleElement().satisfies(item -> {
            assertThat(item.predictionType()).isEqualTo(PredictionTypeEnum.ASIAN);
            assertThat(item.settlementMarkets()).extracting(market -> market.marketType())
                    .containsExactly(MarketTypeEnum.ASIAN_HANDICAP, MarketTypeEnum.ASIAN_TOTAL_GOALS);
            assertThat(item.settlementMarkets().get(0).currentStatus()).isEqualTo(SettlementStatusEnum.HIT);
            assertThat(item.settlementMarkets().get(0).currentSettlementPersisted()).isTrue();
            assertThat(item.settlementMarkets().get(0).profit()).isEqualByComparingTo("0.9200");
            assertThat(item.settlementMarkets().get(1).currentStatus()).isEqualTo(SettlementStatusEnum.PENDING);
            // 只报真正缺失的那一个市场，且不能出现任何 HAD 相关诊断。
            assertThat(item.settlementDiagnostics()).extracting(diagnostic -> diagnostic.code())
                    .containsExactly("SETTLEMENT_MISSING");
            assertThat(item.settlementDiagnostics()).extracting(diagnostic -> diagnostic.marketType())
                    .containsExactly(MarketTypeEnum.ASIAN_TOTAL_GOALS);
        });
    }

    @Test
    void asianWalkOverStatusAndHalfSettlementAreCarriedThroughAsIs() {
        MatchResultFactHistoryVo currentFact = fact(102L, 2, true, MatchResultFactStatusEnum.FINAL);
        when(statusMapper.selectOperationalPredictionId(8L)).thenReturn(8L);
        when(historyRecordAssembler.assemble(List.of(8L))).thenReturn(List.of(asianItem(
                currentFact,
                List.of(
                        new MarketSettlementHistoryVo(MarketTypeEnum.ASIAN_HANDICAP, SettlementStatusEnum.PUSH, true, false,
                                List.of(new SettlementVersionVo(
                                        401L, 1, null, SettlementStatusEnum.PUSH, 102L, "t613-v1", true, NOW, BigDecimal.ZERO))),
                        new MarketSettlementHistoryVo(MarketTypeEnum.ASIAN_TOTAL_GOALS, SettlementStatusEnum.HALF_WIN, true, false,
                                List.of(new SettlementVersionVo(
                                        402L, 1, null, SettlementStatusEnum.HALF_WIN, 102L, "t613-v1", true, NOW,
                                        new BigDecimal("0.4100"))))
                )
        )));

        var detail = service.detail(new AdminPredictionStatusDetailQueryDto(8L));

        assertThat(detail.prediction().settlementMarkets()).extracting(market -> market.currentStatus())
                .containsExactly(SettlementStatusEnum.PUSH, SettlementStatusEnum.HALF_WIN);
        // 走盘与半赢都是终态，不应产生任何缺失或过期诊断。
        assertThat(detail.prediction().settlementDiagnostics()).isEmpty();
        assertThat(detail.settlementMarkets()).extracting(market -> market.currentProfit())
                .containsExactly(BigDecimal.ZERO, new BigDecimal("0.4100"));
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

    /** 亚盘记录：市场集合只有亚盘两个，且不携带竞彩三概率。 */
    private HistoryListItemVo asianItem(
            MatchResultFactHistoryVo currentFact,
            List<MarketSettlementHistoryVo> markets
    ) {
        return new HistoryListItemVo(
                8L, 2, "t306-asian-baseline-v6", "t306-asian-mainline-v6", PredictionStatusEnum.LOCKED,
                null, null, null, null, null, null, null, "c".repeat(64),
                NOW.minusSeconds(3600), NOW.minusSeconds(1800), NOW.minusSeconds(10),
                new HistoryMatchVo(43L, LocalDate.of(2026, 7, 29), "周三043", 1L,
                        "英超", "主队", "客队", NOW.plusSeconds(3600)),
                List.of(currentFact), markets, false,
                PredictionTypeEnum.ASIAN,
                AsianHandicapPickEnum.HOME_COVER,
                TotalGoalsPickEnum.OVER,
                null
        );
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
