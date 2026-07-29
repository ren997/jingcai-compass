package com.jingcaicompass.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.settlement.entity.Settlement;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.exception.SettlementManualReviewException;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import com.jingcaicompass.system.observability.PredictionLifecycleMetrics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** T404 批量隔离和单预测不可变结算写入不依赖 Spring 或数据库实现。 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private SettlementWriter settlementWriter;

    @Mock
    private PredictionLifecycleMetrics lifecycleMetrics;

    @Mock
    private PredictionMapper predictionMapper;

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchResultFactMapper factMapper;

    @Mock
    private SportteryPoolSnapshotMapper poolSnapshotMapper;

    @Mock
    private AuditLogService auditLogService;

    private SettlementService service;
    private SettlementWriter writer;
    private Prediction prediction;
    private MatchResultFact fact;

    @BeforeEach
    void setUp() {
        service = new SettlementServiceImpl(settlementMapper, settlementWriter, lifecycleMetrics);
        writer = new SettlementWriter(
                predictionMapper,
                matchMapper,
                factMapper,
                poolSnapshotMapper,
                settlementMapper,
                new MarketSettlementCalculatorRouter(List.of(
                        new WinDrawLossSettlementCalculator(),
                        new SportteryHandicapSettlementCalculator()
                )),
                auditLogService
        );

        prediction = new Prediction();
        prediction.setId(11L);
        prediction.setMatchId(41L);
        prediction.setPredictionStatus(PredictionStatusEnum.LOCKED);
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setLockTime(Instant.parse("2026-07-27T10:00:00Z"));

        MatchEntity match = new MatchEntity();
        match.setId(41L);
        fact = new MatchResultFact();
        fact.setId(101L);
        fact.setMatchId(41L);
        fact.setFactVersion(1);
        fact.setFactStatus(MatchResultFactStatusEnum.FINAL);
        fact.setMatchStatus(MatchStatusEnum.FINISHED);
        fact.setHomeScore(2);
        fact.setAwayScore(1);

        SportteryPoolSnapshot poolSnapshot = new SportteryPoolSnapshot();
        poolSnapshot.setOfficialHandicap(new BigDecimal("-1"));
        lenient().when(predictionMapper.selectByIdForUpdate(11L)).thenReturn(prediction);
        lenient().when(matchMapper.selectByIdForUpdate(41L)).thenReturn(match);
        lenient().when(factMapper.selectCurrentByMatchId(41L)).thenReturn(fact);
        lenient().when(poolSnapshotMapper.selectLatestOfficialHandicapAtOrBefore(41L, prediction.getLockTime()))
                .thenReturn(poolSnapshot);
        lenient().when(settlementMapper.selectCurrentByPredictionIdAndMarket(11L, MarketTypeEnum.HAD)).thenReturn(null);
        lenient().when(settlementMapper.selectCurrentByPredictionIdAndMarket(11L, MarketTypeEnum.HHAD)).thenReturn(null);
        AtomicLong idSequence = new AtomicLong(801L);
        lenient().when(settlementMapper.insert(any(Settlement.class))).thenAnswer(invocation -> {
            invocation.<Settlement>getArgument(0).setId(idSequence.getAndIncrement());
            return 1;
        });
    }

    @Test
    void keepsBatchProgressWhenOnePredictionNeedsManualReviewOrFails() {
        when(settlementMapper.selectPendingLockedPredictionIds(4)).thenReturn(List.of(1L, 2L, 3L, 4L));
        when(settlementWriter.settlePrediction(1L)).thenReturn(SettlementWriteResult.settled(2));
        when(settlementWriter.settlePrediction(2L)).thenReturn(SettlementWriteResult.skipped());
        when(settlementWriter.settlePrediction(3L)).thenThrow(new SettlementManualReviewException("missing handicap"));
        when(settlementWriter.settlePrediction(4L)).thenThrow(new IllegalStateException("database unavailable"));

        var result = service.settlePendingPredictions(4);

        assertThat(result.candidatePredictionCount()).isEqualTo(4);
        assertThat(result.settledPredictionCount()).isEqualTo(1);
        assertThat(result.settledMarketCount()).isEqualTo(2);
        assertThat(result.skippedPredictionCount()).isEqualTo(1);
        assertThat(result.manualReviewPredictionCount()).isEqualTo(1);
        assertThat(result.failedPredictionCount()).isEqualTo(1);
        verify(lifecycleMetrics).recordSettlementItem("settle", "settled");
        verify(lifecycleMetrics).recordSettlementItem("settle", "skipped");
        verify(lifecycleMetrics).recordSettlementItem("settle", "manual_review");
        verify(lifecycleMetrics).recordSettlementItem("settle", "failed");
    }

    @Test
    void rejectsNonPositiveBatchSizeBeforeQueryingCandidates() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.settlePendingPredictions(0));
        verify(settlementMapper, never()).selectPendingLockedPredictionIds(any(Integer.class));
    }

    @Test
    void appendsBothMarketsWithLockedFactRuleAndAudit() {
        SettlementWriteResult result = writer.settlePrediction(11L);

        ArgumentCaptor<Settlement> settlements = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper, org.mockito.Mockito.times(2)).insert(settlements.capture());
        assertThat(result.outcome()).isEqualTo(SettlementWriteResult.Outcome.SETTLED);
        assertThat(result.settledMarketCount()).isEqualTo(2);
        assertThat(settlements.getAllValues())
                .extracting(Settlement::getMarketType, Settlement::getSettlementStatus, Settlement::getMatchFactId,
                        Settlement::getRuleVersion, Settlement::getSettlementVersion, Settlement::getIsCurrent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MarketTypeEnum.HAD, com.jingcaicompass.settlement.enums.SettlementStatusEnum.HIT,
                                101L, "t403-v1", 1, true),
                        org.assertj.core.groups.Tuple.tuple(MarketTypeEnum.HHAD, com.jingcaicompass.settlement.enums.SettlementStatusEnum.MISS,
                                101L, "t403-v1", 1, true)
                );
        verify(auditLogService, org.mockito.Mockito.times(2)).append(
                eq(SettlementWriter.SYSTEM_OPERATOR),
                eq(AuditTargetTypeEnum.SETTLEMENT),
                any(),
                eq(AuditActionTypeEnum.SETTLE),
                eq("settlement"),
                eq(null),
                org.mockito.ArgumentMatchers.contains("factVersion=1")
        );
    }

    @Test
    void skipsPendingFactsAndDoesNotPersistPendingSettlements() {
        fact.setFactStatus(MatchResultFactStatusEnum.PENDING);
        fact.setHomeScore(null);
        fact.setAwayScore(null);

        SettlementWriteResult result = writer.settlePrediction(11L);

        assertThat(result.outcome()).isEqualTo(SettlementWriteResult.Outcome.SKIPPED);
        verify(settlementMapper, never()).insert(any(Settlement.class));
        verify(auditLogService, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rollsBackPredictionForMissingLockedOfficialHandicapAndMarksItForManualReview() {
        when(poolSnapshotMapper.selectLatestOfficialHandicapAtOrBefore(41L, prediction.getLockTime())).thenReturn(null);

        assertThatThrownBy(() -> writer.settlePrediction(11L))
                .isInstanceOf(SettlementManualReviewException.class)
                .hasMessageContaining("no official handicap snapshot");
        verify(settlementMapper, never()).insert(any(Settlement.class));
        verify(auditLogService, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void repeatAfterBothCurrentSettlementsIsIdempotentlySkipped() {
        Settlement current = new Settlement();
        current.setId(91L);
        when(settlementMapper.selectCurrentByPredictionIdAndMarket(11L, MarketTypeEnum.HAD)).thenReturn(current);
        when(settlementMapper.selectCurrentByPredictionIdAndMarket(11L, MarketTypeEnum.HHAD)).thenReturn(current);

        SettlementWriteResult result = writer.settlePrediction(11L);

        assertThat(result.outcome()).isEqualTo(SettlementWriteResult.Outcome.SKIPPED);
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }
}
