package com.jingcaicompass.snapshot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.service.PredictionContentHasher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnapshotManifestGeneratorTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 7, 26);
    private static final Instant PUBLISH_TIME = Instant.parse("2026-07-26T08:00:00.123456Z");
    private static final Instant LOCK_TIME = Instant.parse("2026-07-26T10:00:00Z");

    private PredictionContentHasher predictionContentHasher;
    private SnapshotManifestGenerator generator;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        predictionContentHasher = new PredictionContentHasher(objectMapper);
        generator = new SnapshotManifestGenerator(objectMapper, predictionContentHasher);
    }

    @Test
    void producesStableGoldenManifestWithFixedFieldsAndFormats() {
        Prediction prediction = publishedPrediction(303_001L, 303_101L, "model-v1", 1);

        var manifest = generator.generate(SNAPSHOT_DATE, List.of(prediction));
        String json = new String(manifest.bytes(), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo(
                "{\"schemaVersion\":1,\"snapshotDate\":\"2026-07-26\","
                        + "\"predictionCount\":1,\"predictions\":[{"
                        + "\"predictionHashSchemaVersion\":1,"
                        + "\"predictionId\":303001,\"matchId\":303101,"
                        + "\"modelVersion\":\"model-v1\","
                        + "\"featureVersion\":\"feature-v1\","
                        + "\"generationBatchId\":\"t305-batch-1\","
                        + "\"generationBatchHash\":\"" + "a".repeat(64) + "\","
                        + "\"predictionVersion\":1,"
                        + "\"homeWinProb\":0.450000,\"drawProb\":0.300000,"
                        + "\"awayWinProb\":0.250000,"
                        + "\"handicapPick\":\"HOME_WIN\","
                        + "\"expectedTotalGoals\":2.50,"
                        + "\"confidenceLevel\":\"HIGH\","
                        + "\"analysisSummary\":\"主队近期状态稳定\","
                        + "\"generatedAt\":\"2026-07-26T07:30:00.654321Z\","
                        + "\"publishTime\":\"2026-07-26T08:00:00.123456Z\","
                        + "\"lockTime\":\"2026-07-26T10:00:00.000000Z\","
                        + "\"predictionHash\":\"" + prediction.getPredictionHash() + "\"}]}"
        );
        assertThat(manifest.sha256())
                .isEqualTo("fe935232bf4bb644e84bd85b8824929f407a2a20a978878514812718775c32ea")
                .matches("^[0-9a-f]{64}$");
        assertThat(manifest.predictionCount()).isEqualTo(1);
    }

    @Test
    void ignoresInputOrderAndUsesStableMatchModelVersionOrder() {
        Prediction later = publishedPrediction(4L, 2L, "model-z", 2);
        Prediction earlier = publishedPrediction(3L, 1L, "model-a", 1);

        var first = generator.generate(SNAPSHOT_DATE, List.of(later, earlier));
        var second = generator.generate(SNAPSHOT_DATE, List.of(earlier, later));

        assertThat(first.bytes()).containsExactly(second.bytes());
        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(new String(first.bytes(), StandardCharsets.UTF_8))
                .containsSubsequence("\"predictionId\":3", "\"predictionId\":4");
    }

    @Test
    void producesDeterministicEmptyManifest() {
        var first = generator.generate(SNAPSHOT_DATE, List.of());
        var second = generator.generate(SNAPSHOT_DATE, List.of());

        assertThat(new String(first.bytes(), StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"schemaVersion\":1,\"snapshotDate\":\"2026-07-26\","
                                + "\"predictionCount\":0,\"predictions\":[]}"
                );
        assertThat(first.bytes()).containsExactly(second.bytes());
        assertThat(first.sha256()).isEqualTo(second.sha256());
    }

    @Test
    void rejectsStoredPredictionHashMismatchAndDraft() {
        Prediction corrupted = publishedPrediction(1L, 1L, "model-v1", 1);
        corrupted.setPredictionHash("f".repeat(64));
        Prediction draft = publishedPrediction(2L, 2L, "model-v1", 1);
        draft.setPredictionStatus(PredictionStatusEnum.DRAFT);

        assertThatThrownBy(() -> generator.generate(SNAPSHOT_DATE, List.of(corrupted)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash mismatch");
        assertThatThrownBy(() -> generator.generate(SNAPSHOT_DATE, List.of(draft)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicly visible");
    }

    @Test
    void rejectsMultipleCurrentVersionsForSameMatchAndModel() {
        Prediction versionOne = publishedPrediction(1L, 10L, "model-v1", 1);
        Prediction versionTwo = publishedPrediction(2L, 10L, "model-v1", 2);

        assertThatThrownBy(() -> generator.generate(
                SNAPSHOT_DATE,
                List.of(versionOne, versionTwo)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple current versions");
    }

    private Prediction publishedPrediction(
            Long predictionId,
            Long matchId,
            String modelVersion,
            int predictionVersion
    ) {
        Prediction prediction = new Prediction();
        prediction.setId(predictionId);
        prediction.setMatchId(matchId);
        prediction.setModelVersion(modelVersion);
        prediction.setFeatureVersion("feature-v1");
        prediction.setGenerationBatchId("t305-batch-1");
        prediction.setGenerationBatchHash("a".repeat(64));
        prediction.setPredictionVersion(predictionVersion);
        prediction.setHomeWinProb(new BigDecimal("0.450000"));
        prediction.setDrawProb(new BigDecimal("0.300000"));
        prediction.setAwayWinProb(new BigDecimal("0.250000"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("主队近期状态稳定");
        prediction.setGeneratedAt(Instant.parse("2026-07-26T07:30:00.654321Z"));
        prediction.setPredictionStatus(PredictionStatusEnum.PUBLISHED);
        prediction.setPublishTime(PUBLISH_TIME);
        prediction.setLockTime(LOCK_TIME);
        prediction.setPredictionHash(
                predictionContentHasher.sha256Hex(prediction, PUBLISH_TIME, LOCK_TIME)
        );
        return prediction;
    }
}
