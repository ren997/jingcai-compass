package com.jingcaicompass.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.jingcaicompass.settlement.dto.SettlementRecalculationBatchResultDto;
import com.jingcaicompass.settlement.entity.Settlement;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.settlement.exception.SettlementManualReviewException;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** T405 重算扫描与版本替代在无 Spring、Mapper 实现或数据库时的行为验证。 */
@ExtendWith(MockitoExtension.class)
class SettlementRecalculationServiceTest {

    @Mock
    private SettlementMapper settlementMapper;

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

    private SettlementRecalculationService service;
    private SettlementRecalculationWriter writer;
    private Prediction prediction;
    private MatchResultFact oldFact;
    private MatchResultFact currentFact;
    private Settlement oldHad;
    private Settlement oldHhad;

    @BeforeEach
    void setUp() {
        writer = new SettlementRecalculationWriter(
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
        service = new SettlementRecalculationServiceImpl(settlementMapper, writer);

        prediction = new Prediction();
        prediction.setId(11L);
        prediction.setMatchId(41L);
        prediction.setPredictionStatus(PredictionStatusEnum.LOCKED);
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setLockTime(Instant.parse("2026-07-27T10:00:00Z"));

        MatchEntity match = new MatchEntity();
        match.setId(41L);
        oldFact = fact(101L, 1, MatchResultFactStatusEnum.FINAL, 2, 1);
        currentFact = fact(102L, 2, MatchResultFactStatusEnum.FINAL, 0, 1);
        oldHad = settlement(201L, MarketTypeEnum.HAD, SettlementStatusEnum.HIT, 101L, 1, "t403-v1");
        oldHhad = settlement(202L, MarketTypeEnum.HHAD, SettlementStatusEnum.HIT, 101L, 1, "t403-v1");

        SportteryPoolSnapshot poolSnapshot = new SportteryPoolSnapshot();
        poolSnapshot.setOfficialHandicap(BigDecimal.ONE);
        lenient().when(predictionMapper.selectByIdForUpdate(11L)).thenReturn(prediction);
        lenient().when(matchMapper.selectByIdForUpdate(41L)).thenReturn(match);
        lenient().when(factMapper.selectCurrentByMatchId(41L)).thenReturn(currentFact);
        lenient().when(factMapper.selectById(101L)).thenReturn(oldFact);
        lenient().when(settlementMapper.selectCurrentByPredictionIdAndMarket(11L, MarketTypeEnum.HAD)).thenReturn(oldHad);
        lenient().when(settlementMapper.selectCurrentByPredictionIdAndMarket(11L, MarketTypeEnum.HHAD)).thenReturn(oldHhad);
        lenient().when(poolSnapshotMapper.selectLatestOfficialHandicapAtOrBefore(41L, prediction.getLockTime()))
                .thenReturn(poolSnapshot);
        lenient().when(settlementMapper.markNotCurrent(any(Long.class))).thenReturn(1);
        AtomicLong sequence = new AtomicLong(301L);
        lenient().when(settlementMapper.insert(any(Settlement.class))).thenAnswer(invocation -> {
            invocation.<Settlement>getArgument(0).setId(sequence.getAndIncrement());
            return 1;
        });
    }

    @Test
    void recalculatesBothMarketsAgainstCorrectedFinalFactAndAuditsVersionChain() {
        SettlementRecalculationWriteResult result = writer.recalculatePrediction(11L);

        ArgumentCaptor<Settlement> replacements = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper, times(2)).insert(replacements.capture());
        assertThat(result.outcome()).isEqualTo(SettlementRecalculationWriteResult.Outcome.RECALCULATED);
        assertThat(result.recalculatedMarketCount()).isEqualTo(2);
        assertThat(replacements.getAllValues())
                .extracting(
                        Settlement::getMarketType,
                        Settlement::getSettlementVersion,
                        Settlement::getSupersedesSettlementVersion,
                        Settlement::getSettlementStatus,
                        Settlement::getMatchFactId,
                        Settlement::getRuleVersion,
                        Settlement::getIsCurrent
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MarketTypeEnum.HAD, 2, 1, SettlementStatusEnum.MISS, 102L, "t403-v1", true
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                MarketTypeEnum.HHAD, 2, 1, SettlementStatusEnum.MISS, 102L, "t403-v1", true
                        )
                );
        verify(settlementMapper).markNotCurrent(201L);
        verify(settlementMapper).markNotCurrent(202L);
        verify(auditLogService, times(2)).append(
                eq(SettlementRecalculationWriter.SYSTEM_OPERATOR),
                eq(AuditTargetTypeEnum.SETTLEMENT),
                any(),
                eq(AuditActionTypeEnum.SUPERSEDE),
                eq("settlementRecalculation"),
                org.mockito.ArgumentMatchers.contains("reason=OFFICIAL_FACT_SUPERSEDED"),
                org.mockito.ArgumentMatchers.contains("matchFactId=102")
        );
    }

    @Test
    void recalculatesFinalToOfficialVoidWithoutReadingHandicap() {
        currentFact = fact(102L, 2, MatchResultFactStatusEnum.VOID, null, null);
        when(factMapper.selectCurrentByMatchId(41L)).thenReturn(currentFact);

        writer.recalculatePrediction(11L);

        ArgumentCaptor<Settlement> replacements = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper, times(2)).insert(replacements.capture());
        assertThat(replacements.getAllValues())
                .extracting(Settlement::getSettlementStatus, Settlement::getMatchFactId)
                .containsOnly(
                        org.assertj.core.groups.Tuple.tuple(SettlementStatusEnum.VOID, 102L)
                );
        verify(poolSnapshotMapper, never()).selectLatestOfficialHandicapAtOrBefore(any(), any());
    }

    @Test
    void repeatAfterCurrentSettlementAlreadyUsesLatestFactIsSkipped() {
        oldHad.setMatchFactId(102L);
        oldHhad.setMatchFactId(102L);

        SettlementRecalculationWriteResult result = writer.recalculatePrediction(11L);

        assertThat(result.outcome()).isEqualTo(SettlementRecalculationWriteResult.Outcome.SKIPPED);
        verify(settlementMapper, never()).markNotCurrent(any());
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    void keepsCurrentSettlementsForUnknownRuleVersionForManualReview() {
        oldHhad.setRuleVersion("t404-v2");

        assertThatThrownBy(() -> writer.recalculatePrediction(11L))
                .isInstanceOf(SettlementManualReviewException.class)
                .hasMessageContaining("unsupported settlement ruleVersion");
        verify(settlementMapper, never()).markNotCurrent(any());
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    void keepsBothMarketsCurrentWhenHistoricalOfficialHandicapIsMissing() {
        when(poolSnapshotMapper.selectLatestOfficialHandicapAtOrBefore(41L, prediction.getLockTime())).thenReturn(null);

        assertThatThrownBy(() -> writer.recalculatePrediction(11L))
                .isInstanceOf(SettlementManualReviewException.class)
                .hasMessageContaining("no official handicap snapshot");
        verify(settlementMapper, never()).markNotCurrent(any());
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    void propagatesAuditFailureSoTheIndependentTransactionCanRollBackAllReplacements() {
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService)
                .append(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> writer.recalculatePrediction(11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        verify(settlementMapper).markNotCurrent(201L);
        verify(settlementMapper).insert(any(Settlement.class));
    }

    @Test
    void isolatesManualReviewAndFailureAcrossBatchCandidates() {
        SettlementRecalculationWriter batchWriter = org.mockito.Mockito.mock(SettlementRecalculationWriter.class);
        SettlementRecalculationService batchService = new SettlementRecalculationServiceImpl(settlementMapper, batchWriter);
        when(settlementMapper.selectOutdatedLockedPredictionIds(4)).thenReturn(List.of(1L, 2L, 3L, 4L));
        when(batchWriter.recalculatePrediction(1L)).thenReturn(SettlementRecalculationWriteResult.recalculated(2));
        when(batchWriter.recalculatePrediction(2L)).thenReturn(SettlementRecalculationWriteResult.skipped());
        when(batchWriter.recalculatePrediction(3L)).thenThrow(new SettlementManualReviewException("unknown rule"));
        when(batchWriter.recalculatePrediction(4L)).thenThrow(new IllegalStateException("database unavailable"));

        SettlementRecalculationBatchResultDto result = batchService.recalculateOutdatedSettlements(4);

        assertThat(result.candidatePredictionCount()).isEqualTo(4);
        assertThat(result.recalculatedPredictionCount()).isEqualTo(1);
        assertThat(result.recalculatedMarketCount()).isEqualTo(2);
        assertThat(result.skippedPredictionCount()).isEqualTo(1);
        assertThat(result.manualReviewPredictionCount()).isEqualTo(1);
        assertThat(result.failedPredictionCount()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveBatchSizeBeforeQueryingCandidates() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.recalculateOutdatedSettlements(0));
        verify(settlementMapper, never()).selectOutdatedLockedPredictionIds(any(Integer.class));
    }

    private MatchResultFact fact(
            Long id,
            int version,
            MatchResultFactStatusEnum status,
            Integer homeScore,
            Integer awayScore
    ) {
        MatchResultFact fact = new MatchResultFact();
        fact.setId(id);
        fact.setMatchId(41L);
        fact.setFactVersion(version);
        fact.setFactStatus(status);
        fact.setMatchStatus(status == MatchResultFactStatusEnum.FINAL ? MatchStatusEnum.FINISHED : MatchStatusEnum.CANCELLED);
        fact.setHomeScore(homeScore);
        fact.setAwayScore(awayScore);
        return fact;
    }

    private Settlement settlement(
            Long id,
            MarketTypeEnum marketType,
            SettlementStatusEnum status,
            Long factId,
            int version,
            String ruleVersion
    ) {
        Settlement settlement = new Settlement();
        settlement.setId(id);
        settlement.setPredictionId(11L);
        settlement.setMarketType(marketType);
        settlement.setSettlementVersion(version);
        settlement.setSupersedesSettlementVersion(version == 1 ? null : version - 1);
        settlement.setSettlementStatus(status);
        settlement.setMatchFactId(factId);
        settlement.setRuleVersion(ruleVersion);
        settlement.setIsCurrent(true);
        return settlement;
    }
}
