package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.prediction.dto.PredictionDetailQueryDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.snapshot.entity.PredictionSnapshot;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import com.jingcaicompass.snapshot.storage.SnapshotStorage;
import com.jingcaicompass.system.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicPredictionQueryServiceTest {

    private MatchMapper matchMapper;
    private PredictionMapper predictionMapper;
    private PredictionSnapshotMapper snapshotMapper;
    private SnapshotStorage snapshotStorage;
    private PublicPredictionQueryService queryService;

    @BeforeEach
    void setUp() {
        matchMapper = mock(MatchMapper.class);
        predictionMapper = mock(PredictionMapper.class);
        snapshotMapper = mock(PredictionSnapshotMapper.class);
        snapshotStorage = mock(SnapshotStorage.class);
        when(snapshotStorage.storageType()).thenReturn(SnapshotStorageTypeEnum.LOCAL);
        queryService = new PublicPredictionQueryServiceImpl(
                matchMapper,
                predictionMapper,
                snapshotMapper,
                snapshotStorage,
                new ObjectMapper()
        );
    }

    @Test
    void returnsEveryCurrentModelWithPublicHistoryAndVerifiedExactSnapshot() throws Exception {
        MatchEntity match = match(101L);
        Prediction alphaV1 = prediction(11L, "model-alpha", 1, PredictionStatusEnum.PUBLISHED, "a".repeat(64));
        Prediction alphaV2 = prediction(12L, "model-alpha", 2, PredictionStatusEnum.LOCKED, "b".repeat(64));
        Prediction betaV1 = prediction(13L, "model-beta", 1, PredictionStatusEnum.PUBLISHED, "c".repeat(64));
        PredictionSnapshot snapshot = snapshot(301L, match.getLotteryDate(), "d".repeat(64));
        String manifest = """
                {"schemaVersion":1,"snapshotDate":"2026-07-28","predictionCount":1,"predictions":[
                {"predictionHashSchemaVersion":1,"predictionId":12,"matchId":101,"modelVersion":"model-alpha","featureVersion":"feature-v1","generationBatchId":"batch-1","generationBatchHash":"%s","predictionVersion":2,"homeWinProb":0.450000,"drawProb":0.300000,"awayWinProb":0.250000,"handicapPick":"HOME_WIN","expectedTotalGoals":2.50,"confidenceLevel":"HIGH","analysisSummary":"公开分析摘要","generatedAt":"2026-07-28T08:00:00.000000Z","publishTime":"2026-07-28T08:01:00.000000Z","lockTime":"2026-07-28T12:00:00.000000Z","predictionHash":"%s"}]}
                """.formatted("e".repeat(64), alphaV2.getPredictionHash());
        when(matchMapper.selectById(101L)).thenReturn(match);
        when(predictionMapper.selectPublicByMatchId(101L)).thenReturn(List.of(alphaV1, alphaV2, betaV1));
        when(snapshotMapper.selectPublishedByDate(match.getLotteryDate())).thenReturn(List.of(snapshot));
        when(snapshotStorage.verify(snapshot.getObjectKey(), snapshot.getSnapshotHash(), snapshot.getContentLength()))
                .thenReturn(true);
        when(snapshotStorage.open(snapshot.getObjectKey())).thenAnswer(ignored ->
                new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));

        var detail = queryService.detail(new PredictionDetailQueryDto(101L));

        assertThat(detail.modelPredictions()).hasSize(2);
        assertThat(detail.modelPredictions().getFirst()).satisfies(model -> {
            assertThat(model.modelVersion()).isEqualTo("model-alpha");
            assertThat(model.currentPrediction().predictionId()).isEqualTo(12L);
            assertThat(model.currentPrediction().replacesPredictionId()).isEqualTo(11L);
            assertThat(model.currentPrediction().snapshot()).isNotNull();
            assertThat(model.historicalPredictions()).singleElement().satisfies(history -> {
                assertThat(history.predictionId()).isEqualTo(11L);
                assertThat(history.replacesPredictionId()).isNull();
            });
        });
        assertThat(detail.modelPredictions().get(1).currentPrediction().predictionId()).isEqualTo(13L);
        assertThat(detail.modelPredictions().get(1).currentPrediction().snapshot()).isNull();
    }

    @Test
    void doesNotAssociateMismatchedOrUnreadablePublishedSnapshots() {
        MatchEntity match = match(102L);
        Prediction prediction = prediction(21L, "model-main", 1, PredictionStatusEnum.PUBLISHED, "a".repeat(64));
        PredictionSnapshot snapshot = snapshot(302L, match.getLotteryDate(), "b".repeat(64));
        when(matchMapper.selectById(102L)).thenReturn(match);
        when(predictionMapper.selectPublicByMatchId(102L)).thenReturn(List.of(prediction));
        when(snapshotMapper.selectPublishedByDate(match.getLotteryDate())).thenReturn(List.of(snapshot));
        when(snapshotStorage.verify(snapshot.getObjectKey(), snapshot.getSnapshotHash(), snapshot.getContentLength()))
                .thenReturn(false);

        var detail = queryService.detail(new PredictionDetailQueryDto(102L));

        assertThat(detail.modelPredictions()).singleElement().satisfies(model -> {
            assertThat(model.currentPrediction().snapshot()).isNull();
            assertThat(model.currentPrediction().snapshotAvailability().name()).isEqualTo("UNAVAILABLE");
        });
    }

    @Test
    void verifiesPublishedSnapshotAndRejectsMissingOrUnreadableDownload() {
        PredictionSnapshot snapshot = snapshot(303L, LocalDate.of(2026, 7, 28), "c".repeat(64));
        when(snapshotMapper.selectPublishedById(303L)).thenReturn(snapshot);
        when(snapshotStorage.verify(snapshot.getObjectKey(), snapshot.getSnapshotHash(), snapshot.getContentLength()))
                .thenReturn(false);

        assertThat(queryService.verifySnapshot(303L).verified()).isFalse();
        assertThatThrownBy(() -> queryService.openSnapshot(303L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode().code())
                        .isEqualTo("PREDICTION_SNAPSHOT_UNAVAILABLE"));
        assertThatThrownBy(() -> queryService.verifySnapshot(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode().code())
                        .isEqualTo("PREDICTION_SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void returnsEmptyDetailForExistingMatchWithoutAnyPublicPrediction() {
        when(matchMapper.selectById(103L)).thenReturn(match(103L));
        when(predictionMapper.selectPublicByMatchId(103L)).thenReturn(List.of());

        assertThat(queryService.detail(new PredictionDetailQueryDto(103L)).modelPredictions()).isEmpty();
    }

    private MatchEntity match(long id) {
        MatchEntity match = new MatchEntity();
        match.setId(id);
        match.setLotteryDate(LocalDate.of(2026, 7, 28));
        return match;
    }

    private Prediction prediction(
            long id,
            String modelVersion,
            int version,
            PredictionStatusEnum status,
            String predictionHash
    ) {
        Prediction prediction = new Prediction();
        prediction.setId(id);
        prediction.setMatchId(101L);
        prediction.setModelVersion(modelVersion);
        prediction.setFeatureVersion("feature-v1");
        prediction.setPredictionVersion(version);
        prediction.setPredictionStatus(status);
        prediction.setHomeWinProb(new BigDecimal("0.45"));
        prediction.setDrawProb(new BigDecimal("0.30"));
        prediction.setAwayWinProb(new BigDecimal("0.25"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("公开分析摘要");
        prediction.setGeneratedAt(Instant.parse("2026-07-28T08:00:00Z"));
        prediction.setPublishTime(Instant.parse("2026-07-28T08:01:00Z"));
        prediction.setLockTime(Instant.parse("2026-07-28T12:00:00Z"));
        prediction.setPredictionHash(predictionHash);
        return prediction;
    }

    private PredictionSnapshot snapshot(long id, LocalDate date, String snapshotHash) {
        PredictionSnapshot snapshot = new PredictionSnapshot();
        snapshot.setId(id);
        snapshot.setSnapshotDate(date);
        snapshot.setSnapshotVersion(2);
        snapshot.setSnapshotStatus(PredictionSnapshotStatusEnum.PUBLISHED);
        snapshot.setSnapshotHash(snapshotHash);
        snapshot.setStorageType("LOCAL");
        snapshot.setObjectKey("prediction-snapshots/" + date + "/v000002-" + snapshotHash + ".json");
        snapshot.setContentType("application/json");
        snapshot.setContentLength(512L);
        snapshot.setPublishedAt(Instant.parse("2026-07-28T09:00:00Z"));
        return snapshot;
    }
}
