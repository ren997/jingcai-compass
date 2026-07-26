package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.prediction.dto.PredictionImportBatchDto;
import com.jingcaicompass.prediction.dto.PredictionImportDto;
import com.jingcaicompass.prediction.dto.PredictionImportResultDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PredictionImportServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Mock
    private PredictionImportFileParser fileParser;

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private PredictionMapper predictionMapper;

    @Mock
    private PredictionImportWriter importWriter;

    private PredictionImportService service;

    @BeforeEach
    void setUp() {
        service = new PredictionImportServiceImpl(
                fileParser,
                matchMapper,
                predictionMapper,
                importWriter,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void normalizesBoundaryValuesAllocatesNextVersionAndWritesDraftBatch() {
        List<PredictionImportDto> items = List.of(
                item(1L, "0", "0", "1", "0", "  第一场分析  "),
                item(2L, "0.333333", "0.333333", "0.333333", "2.5", "第二场分析"),
                item(3L, "0.333334", "0.333334", "0.333333", "999.99", "第三场分析")
        );
        when(fileParser.parse(any())).thenReturn(batch("batch-boundary", items));
        when(predictionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(matchMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                match(1L, MatchStatusEnum.SCHEDULED, NOW.plusSeconds(3600)),
                match(2L, MatchStatusEnum.LOCKED, NOW.plusSeconds(7200)),
                match(3L, MatchStatusEnum.SCHEDULED, NOW.plusSeconds(10800))
        ));
        Prediction latest = new Prediction();
        latest.setPredictionVersion(2);
        when(predictionMapper.selectOne(any(Wrapper.class))).thenReturn(latest, null, null);
        when(importWriter.writeAll(anyList())).thenAnswer(invocation -> {
            List<Prediction> predictions = invocation.getArgument(0);
            for (int index = 0; index < predictions.size(); index++) {
                predictions.get(index).setId(101L + index);
            }
            return predictions;
        });

        PredictionImportResultDto result = service.importFile(new byte[] {1});

        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.insertedCount()).isEqualTo(3);
        assertThat(result.reusedCount()).isZero();
        assertThat(result.predictionIds()).containsExactly(101L, 102L, 103L);

        ArgumentCaptor<List<Prediction>> captor = ArgumentCaptor.forClass(List.class);
        verify(importWriter).writeAll(captor.capture());
        List<Prediction> saved = captor.getValue();
        assertThat(saved).extracting(Prediction::getPredictionVersion).containsExactly(3, 1, 1);
        assertThat(saved).extracting(Prediction::getPredictionStatus)
                .containsOnly(PredictionStatusEnum.DRAFT);
        assertThat(saved).allSatisfy(prediction -> {
            assertThat(prediction.getPublishTime()).isNull();
            assertThat(prediction.getLockTime()).isNull();
            assertThat(prediction.getPredictionHash()).isNull();
            assertThat(prediction.getHomeWinProb().scale()).isEqualTo(6);
            assertThat(prediction.getExpectedTotalGoals().scale()).isEqualTo(2);
        });
        assertThat(saved.get(0).getAnalysisSummary()).isEqualTo("第一场分析");
        assertThat(saved.get(0).getGeneratedAt())
                .isEqualTo(Instant.parse("2026-07-26T00:00:00.123456Z"));
    }

    @ParameterizedTest
    @MethodSource("invalidNumericAndSummaryItems")
    void rejectsInvalidNumbersAndPromiseLanguageBeforeDatabaseAccess(
            PredictionImportDto item,
            String expectedMessage
    ) {
        when(fileParser.parse(any())).thenReturn(batch("batch-invalid", List.of(item)));

        assertThatThrownBy(() -> service.importFile(new byte[] {1}))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(exception).hasMessageContaining(expectedMessage);
                });
        verifyNoInteractions(matchMapper, predictionMapper, importWriter);
    }

    @Test
    void rejectsDuplicateMatchModelBeforeDatabaseAccess() {
        PredictionImportDto first = validItem(1L);
        PredictionImportDto duplicate = new PredictionImportDto(
                1L,
                first.modelVersion(),
                "feature-v2",
                first.homeWinProb(),
                first.drawProb(),
                first.awayWinProb(),
                first.handicapPick(),
                first.expectedTotalGoals(),
                first.confidenceLevel(),
                first.analysisSummary(),
                first.generatedAt()
        );
        when(fileParser.parse(any())).thenReturn(batch("batch-duplicate", List.of(first, duplicate)));

        assertInvalid(() -> service.importFile(new byte[] {1}), "duplicate match/model");
        verifyNoInteractions(matchMapper, predictionMapper, importWriter);
    }

    @Test
    void rejectsMissingStartedAndUnsupportedMatchesWithoutWriting() {
        PredictionImportDto item = validItem(1L);
        when(fileParser.parse(any())).thenReturn(batch("batch-missing", List.of(item)));
        when(predictionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(matchMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        assertInvalid(() -> service.importFile(new byte[] {1}), "match not found");
        verify(importWriter, never()).writeAll(anyList());
    }

    @ParameterizedTest
    @MethodSource("nonImportableMatches")
    void rejectsStartedOrUnsupportedMatch(MatchEntity match, String expectedMessage) {
        when(fileParser.parse(any())).thenReturn(batch("batch-match", List.of(validItem(match.getId()))));
        when(predictionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(matchMapper.selectBatchIds(anyCollection())).thenReturn(List.of(match));

        assertInvalid(() -> service.importFile(new byte[] {1}), expectedMessage);
        verify(importWriter, never()).writeAll(anyList());
    }

    @Test
    void reusesExactBatchWithoutRevalidatingMatchTime() {
        PredictionImportDto item = validItem(1L);
        Prediction existing = prediction(91L, "batch-reuse", HASH, 1, item);
        existing.setPredictionStatus(PredictionStatusEnum.PUBLISHED);
        when(fileParser.parse(any())).thenReturn(batch("batch-reuse", List.of(item)));
        when(predictionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        PredictionImportResultDto result = service.importFile(new byte[] {1});

        assertThat(result.insertedCount()).isZero();
        assertThat(result.reusedCount()).isOne();
        assertThat(result.predictionIds()).containsExactly(91L);
        verifyNoInteractions(matchMapper, importWriter);
        verify(predictionMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void rejectsSameBatchIdWithDifferentHashOrIncompleteRecordSet() {
        PredictionImportDto item = validItem(1L);
        Prediction existing = prediction(91L, "batch-conflict", "b".repeat(64), 1, item);
        when(fileParser.parse(any())).thenReturn(batch("batch-conflict", List.of(item)));
        when(predictionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        assertBusinessConflict(() -> service.importFile(new byte[] {1}));
        verifyNoInteractions(matchMapper, importWriter);
    }

    @Test
    void translatesDatabaseUniqueConflictToBatchBusinessConflict() {
        PredictionImportDto item = validItem(1L);
        when(fileParser.parse(any())).thenReturn(batch("batch-race", List.of(item)));
        when(predictionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(matchMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                match(1L, MatchStatusEnum.SCHEDULED, NOW.plusSeconds(3600))
        ));
        when(predictionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(importWriter.writeAll(anyList()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertBusinessConflict(() -> service.importFile(new byte[] {1}));
    }

    @Test
    void secondInvalidItemPreventsAnyReadOrWrite() {
        PredictionImportDto invalidSecond = item(
                2L,
                "0.4",
                "0.3",
                "0.2",
                "2.5",
                "第二场分析"
        );
        when(fileParser.parse(any())).thenReturn(batch(
                "batch-all-or-nothing",
                List.of(validItem(1L), invalidSecond)
        ));

        assertInvalid(() -> service.importFile(new byte[] {1}), "probability sum");
        verifyNoInteractions(matchMapper, predictionMapper, importWriter);
    }

    private static Stream<Arguments> invalidNumericAndSummaryItems() {
        return Stream.of(
                Arguments.of(item(1L, "0.4000000", "0.3", "0.3", "2.5", "分析"),
                        "at most 6 decimal"),
                Arguments.of(item(1L, "0.4", "0.3", "0.2", "2.5", "分析"),
                        "probability sum"),
                Arguments.of(item(1L, "0.4", "0.3", "0.3", "-0.01", "分析"),
                        "between 0 and 999.99"),
                Arguments.of(item(1L, "0.4", "0.3", "0.3", "2.555", "分析"),
                        "at most 2 decimal"),
                Arguments.of(item(1L, "0.4", "0.3", "0.3", "2.5", "本场保证收益"),
                        "prohibited promise")
        );
    }

    private static Stream<Arguments> nonImportableMatches() {
        return Stream.of(
                Arguments.of(
                        match(1L, MatchStatusEnum.SCHEDULED, NOW),
                        "already started"
                ),
                Arguments.of(
                        match(1L, MatchStatusEnum.FINISHED, NOW.plusSeconds(3600)),
                        "status does not allow"
                )
        );
    }

    private static PredictionImportBatchDto batch(String batchId, List<PredictionImportDto> items) {
        return new PredictionImportBatchDto(batchId, HASH, items);
    }

    private static PredictionImportDto validItem(Long matchId) {
        return item(matchId, "0.4", "0.3", "0.3", "2.5", "两队均有机会，需关注临场变化");
    }

    private static PredictionImportDto item(
            Long matchId,
            String home,
            String draw,
            String away,
            String goals,
            String summary
    ) {
        return new PredictionImportDto(
                matchId,
                "model-v1",
                "feature-v1",
                new BigDecimal(home),
                new BigDecimal(draw),
                new BigDecimal(away),
                HandicapPickEnum.HOME_WIN,
                new BigDecimal(goals),
                ConfidenceLevelEnum.HIGH,
                summary,
                Instant.parse("2026-07-26T00:00:00.123456789Z")
        );
    }

    private static MatchEntity match(Long id, MatchStatusEnum status, Instant kickoff) {
        MatchEntity match = new MatchEntity();
        match.setId(id);
        match.setMatchStatus(status);
        match.setKickoffTime(kickoff);
        return match;
    }

    private static Prediction prediction(
            Long id,
            String batchId,
            String hash,
            int version,
            PredictionImportDto item
    ) {
        Prediction prediction = new Prediction();
        prediction.setId(id);
        prediction.setMatchId(item.matchId());
        prediction.setModelVersion(item.modelVersion());
        prediction.setFeatureVersion(item.featureVersion());
        prediction.setGenerationBatchId(batchId);
        prediction.setGenerationBatchHash(hash);
        prediction.setPredictionVersion(version);
        prediction.setHomeWinProb(item.homeWinProb().setScale(6));
        prediction.setDrawProb(item.drawProb().setScale(6));
        prediction.setAwayWinProb(item.awayWinProb().setScale(6));
        prediction.setHandicapPick(item.handicapPick());
        prediction.setExpectedTotalGoals(item.expectedTotalGoals().setScale(2));
        prediction.setConfidenceLevel(item.confidenceLevel());
        prediction.setAnalysisSummary(item.analysisSummary().trim());
        prediction.setGeneratedAt(item.generatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        prediction.setPredictionStatus(PredictionStatusEnum.DRAFT);
        return prediction;
    }

    private void assertInvalid(ThrowingOperation operation, String message) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(exception).hasMessageContaining(message);
                });
    }

    private void assertBusinessConflict(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.BUSINESS_ERROR));
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run();
    }
}
