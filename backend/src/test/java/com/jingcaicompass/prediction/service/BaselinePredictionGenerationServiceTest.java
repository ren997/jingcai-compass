package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.prediction.dto.PredictionImportBatchDto;
import com.jingcaicompass.prediction.dto.PredictionImportResultDto;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** T306 可解释预测基线的确定性与数据门槛测试。 */
@ExtendWith(MockitoExtension.class)
class BaselinePredictionGenerationServiceTest {

    private static final LocalDate LOTTERY_DATE = LocalDate.of(2026, 8, 11);
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @Mock
    private MatchMapper matchMapper;
    @Mock
    private SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper;
    @Mock
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    @Mock
    private PredictionMapper predictionMapper;
    @Mock
    private PredictionImportService predictionImportService;

    private PredictionImportFileParser parser;
    private BaselinePredictionGenerationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        parser = new PredictionImportFileParserImpl(objectMapper);
        service = new BaselinePredictionGenerationServiceImpl(
                matchMapper,
                sportteryPoolSnapshotMapper,
                asianOddsSnapshotMapper,
                predictionMapper,
                predictionImportService,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void generatesDeterministicDraftInputFromConfirmedMarkets() {
        MatchEntity match = scheduledMatch(101L);
        when(matchMapper.selectPublicDailyMatches(LOTTERY_DATE)).thenReturn(List.of(match));
        when(asianOddsSnapshotMapper.selectLatestCompleteConfirmedLinesByMatchIds(List.of(101L)))
                .thenReturn(List.of(asian(101L)));
        when(predictionImportService.importFile(any())).thenAnswer(invocation -> {
            PredictionImportBatchDto batch = parser.parse(invocation.getArgument(0));
            return new PredictionImportResultDto(
                    batch.generationBatchId(),
                    batch.generationBatchHash(),
                    1,
                    1,
                    0,
                    List.of(9001L)
            );
        });

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(predictionImportService).importFile(contentCaptor.capture());
        PredictionImportBatchDto batch = parser.parse(contentCaptor.getValue());
        assertThat(batch.generationBatchId()).startsWith("t306-asian-2026-08-11-");
        assertThat(batch.predictions()).singleElement().satisfies(prediction -> {
            assertThat(prediction.matchId()).isEqualTo(101L);
            assertThat(prediction.modelVersion()).isEqualTo(BaselinePredictionGenerationServiceImpl.MODEL_VERSION);
            assertThat(prediction.featureVersion()).isEqualTo(BaselinePredictionGenerationServiceImpl.FEATURE_VERSION);
            assertThat(prediction.homeWinProb()).isNull();
            assertThat(prediction.drawProb()).isNull();
            assertThat(prediction.awayWinProb()).isNull();
            assertThat(prediction.handicapPick()).isNull();
            assertThat(prediction.expectedTotalGoals()).isNull();
            assertThat(prediction.asianOddsSnapshotId()).isEqualTo(501L);
            assertThat(prediction.asianHandicapPick()).isNull();
            assertThat(prediction.totalGoalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
            assertThat(prediction.asianHandicapConfidenceLevel()).isNull();
            assertThat(prediction.totalGoalsConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
            assertThat(prediction.confidenceLevel()).isNull();
            assertThat(prediction.analysisSummary()).contains("亚盘专用", "低置信度不发布", "不代表独立校准概率");
            assertThat(prediction.generatedAt()).isEqualTo(Instant.parse("2026-08-10T02:00:00Z"));
        });
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(result.reusedCount()).isZero();
        assertThat(result.generationBatchId()).isEqualTo(batch.generationBatchId());
        assertThat(result.generatedAt()).isEqualTo(Instant.parse("2026-08-10T02:00:00Z"));
        assertThat(result.skippedByReason()).isEmpty();
    }

    @Test
    void skipsMatchWithoutConfirmedCompleteAsianMarketAndDoesNotImportEmptyBatch() {
        MatchEntity match = scheduledMatch(102L);
        when(matchMapper.selectPublicDailyMatches(LOTTERY_DATE)).thenReturn(List.of(match));
        when(asianOddsSnapshotMapper.selectLatestCompleteConfirmedLinesByMatchIds(List.of(102L)))
                .thenReturn(List.of());

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        verify(predictionImportService, never()).importFile(any());
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.generatedCount()).isZero();
        assertThat(result.generationBatchId()).isNull();
        assertThat(result.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET,
                1
        );
    }

    private MatchEntity scheduledMatch(long id) {
        MatchEntity match = new MatchEntity();
        match.setId(id);
        match.setMatchStatus(MatchStatusEnum.SCHEDULED);
        match.setKickoffTime(NOW.plusSeconds(86_400));
        return match;
    }

    private SportteryPoolSnapshot sporttery(long matchId) {
        SportteryPoolSnapshot snapshot = new SportteryPoolSnapshot();
        snapshot.setMatchId(matchId);
        snapshot.setOfficialHandicap(new BigDecimal("-1"));
        snapshot.setHadHomeSp(new BigDecimal("2.00"));
        snapshot.setHadDrawSp(new BigDecimal("4.00"));
        snapshot.setHadAwaySp(new BigDecimal("4.00"));
        snapshot.setHhadHomeSp(new BigDecimal("1.70"));
        snapshot.setHhadDrawSp(new BigDecimal("3.20"));
        snapshot.setHhadAwaySp(new BigDecimal("5.00"));
        snapshot.setCapturedAt(Instant.parse("2026-08-10T01:00:00Z"));
        return snapshot;
    }

    private AsianOddsSnapshot asian(long matchId) {
        AsianOddsSnapshot snapshot = new AsianOddsSnapshot();
        snapshot.setId(501L);
        snapshot.setMatchId(matchId);
        snapshot.setProviderCode("ASIAN_TEST");
        snapshot.setBookmakerCode("BOOK_A");
        snapshot.setHandicapLine(new BigDecimal("-0.50"));
        snapshot.setHomeOdds(new BigDecimal("1.90"));
        snapshot.setAwayOdds(new BigDecimal("1.90"));
        snapshot.setTotalLine(new BigDecimal("2.50"));
        snapshot.setOverOdds(new BigDecimal("1.80"));
        snapshot.setUnderOdds(new BigDecimal("2.00"));
        snapshot.setCapturedAt(Instant.parse("2026-08-10T02:00:00Z"));
        return snapshot;
    }
}
