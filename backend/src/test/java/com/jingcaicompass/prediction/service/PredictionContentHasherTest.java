package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PredictionContentHasherTest {

    private static final Instant PUBLISH_TIME = Instant.parse("2026-07-26T08:00:00.123456Z");
    private static final Instant LOCK_TIME = Instant.parse("2026-07-26T10:00:00.000000Z");

    private PredictionContentHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new PredictionContentHasher(new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void producesStableGoldenHashWithFixedScalesAndUtcMicroseconds() {
        Prediction first = prediction();
        Prediction sameValuesWithDifferentScales = prediction();
        sameValuesWithDifferentScales.setHomeWinProb(new BigDecimal("0.45"));
        sameValuesWithDifferentScales.setDrawProb(new BigDecimal("0.30"));
        sameValuesWithDifferentScales.setAwayWinProb(new BigDecimal("0.25"));
        sameValuesWithDifferentScales.setExpectedTotalGoals(new BigDecimal("2.5"));

        String firstHash = hasher.sha256Hex(first, PUBLISH_TIME, LOCK_TIME);
        String secondHash = hasher.sha256Hex(
                sameValuesWithDifferentScales,
                PUBLISH_TIME.plusNanos(999),
                LOCK_TIME.plusNanos(999)
        );

        assertThat(firstHash)
                .isEqualTo("3cca51633125f5d94a9ac0f9e621738c801903718143e6b70f14791b000c8cc8")
                .isEqualTo(secondHash)
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void includesBoundAsianMarketDirectionsInV2Hash() {
        Prediction prediction = prediction();
        prediction.setAsianOddsSnapshotId(901L);
        prediction.setAsianHandicapPick(AsianHandicapPickEnum.AWAY_COVER);
        prediction.setTotalGoalsPick(TotalGoalsPickEnum.UNDER);

        String first = hasher.sha256Hex(prediction, PUBLISH_TIME, LOCK_TIME);
        prediction.setTotalGoalsPick(TotalGoalsPickEnum.OVER);
        String second = hasher.sha256Hex(prediction, PUBLISH_TIME, LOCK_TIME);

        assertThat(hasher.hashSchemaVersion(prediction)).isEqualTo(PredictionContentHasher.HASH_SCHEMA_VERSION);
        assertThat(first).isNotEqualTo(second).matches("^[0-9a-f]{64}$");
    }

    @Test
    void changesHashWhenPublishedContentChanges() {
        Prediction changed = prediction();
        changed.setAnalysisSummary("不同的公开分析");

        assertThat(hasher.sha256Hex(changed, PUBLISH_TIME, LOCK_TIME))
                .isNotEqualTo(hasher.sha256Hex(prediction(), PUBLISH_TIME, LOCK_TIME));
    }

    @Test
    void rejectsInvalidProbabilityAndTimeBoundary() {
        Prediction invalid = prediction();
        invalid.setAwayWinProb(new BigDecimal("0.20"));

        assertThatThrownBy(() -> hasher.sha256Hex(invalid, PUBLISH_TIME, LOCK_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probability sum");
        assertThatThrownBy(() -> hasher.sha256Hex(prediction(), LOCK_TIME, LOCK_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before lockTime");
    }

    @Test
    void keepsAsianV3HashWhenModelProbabilitiesAreAbsent() {
        Prediction asian = asianPrediction();

        assertThat(hasher.hashSchemaVersion(asian)).isEqualTo(PredictionContentHasher.ASIAN_HASH_SCHEMA_VERSION);
        assertThat(hasher.sha256Hex(asian, PUBLISH_TIME, LOCK_TIME))
                .isEqualTo("7b594efa0c31948c6ff16cea0258afc38eba52916eda6a6fcab26795649bf186");
    }

    @Test
    void includesModelProbabilitiesInAsianV4Hash() {
        Prediction withoutProbabilities = asianPrediction();
        Prediction asian = asianPrediction();
        populateModelProbabilities(asian);

        String v3 = hasher.sha256Hex(withoutProbabilities, PUBLISH_TIME, LOCK_TIME);
        String v4 = hasher.sha256Hex(asian, PUBLISH_TIME, LOCK_TIME);

        assertThat(hasher.hashSchemaVersion(asian))
                .isEqualTo(PredictionContentHasher.ASIAN_PROBABILITY_HASH_SCHEMA_VERSION);
        assertThat(v4).isNotEqualTo(v3).isEqualTo("824dffaeb08ec1bd762c8e7771c522e8b5d12431bc825d63ed86505cb99c7d61");
    }

    /**
     * 十列必须全有或全无：部分落库的行既复算不出 v3 也复算不出 v4。
     * 放过它等于允许公开快照的摘要与库内哈希长期不一致且无人发现。
     */
    @Test
    void rejectsPartiallyPopulatedModelProbabilities() {
        Prediction partial = asianPrediction();
        partial.setLambdaHome(new BigDecimal("1.420000"));

        assertThatThrownBy(() -> hasher.sha256Hex(partial, PUBLISH_TIME, LOCK_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all present or all absent");
        assertThatThrownBy(() -> hasher.hashSchemaVersion(partial))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * V24 上线到 v4 结构上线之间的存量行：十列已填但哈希按 v3 算。
     * 复算必须回落到 v3 接受它，否则快照生成会整批失败。
     */
    @Test
    void acceptsStoredV3HashForRowThatAlreadyHasModelProbabilities() {
        Prediction asian = asianPrediction();
        String v3 = hasher.sha256Hex(asian, PUBLISH_TIME, LOCK_TIME);
        populateModelProbabilities(asian);
        asian.setPredictionHash(v3);

        assertThat(hasher.sha256Hex(asian, PUBLISH_TIME, LOCK_TIME)).isNotEqualTo(v3);
        assertThat(hasher.verifyStoredHash(asian, PUBLISH_TIME, LOCK_TIME))
                .isEqualTo(new PredictionContentHasher.ResolvedPredictionHash(
                        PredictionContentHasher.ASIAN_HASH_SCHEMA_VERSION,
                        v3
                ));
    }

    @Test
    void reportsV4VersionWhenStoredHashMatchesCurrentStructure() {
        Prediction asian = asianPrediction();
        populateModelProbabilities(asian);
        asian.setPredictionHash(hasher.sha256Hex(asian, PUBLISH_TIME, LOCK_TIME));

        assertThat(hasher.verifyStoredHash(asian, PUBLISH_TIME, LOCK_TIME).schemaVersion())
                .isEqualTo(PredictionContentHasher.ASIAN_PROBABILITY_HASH_SCHEMA_VERSION);
    }

    @Test
    void rejectsStoredHashThatMatchesNeitherStructure() {
        Prediction asian = asianPrediction();
        populateModelProbabilities(asian);
        asian.setPredictionHash("f".repeat(64));

        assertThatThrownBy(() -> hasher.verifyStoredHash(asian, PUBLISH_TIME, LOCK_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prediction hash mismatch");
    }

    private Prediction asianPrediction() {
        Prediction prediction = new Prediction();
        prediction.setPredictionType(PredictionTypeEnum.ASIAN);
        prediction.setId(303_011L);
        prediction.setMatchId(303_111L);
        prediction.setModelVersion("t306-asian-baseline-v6");
        prediction.setFeatureVersion("feature-v1");
        prediction.setGenerationBatchId("t613-batch-1");
        prediction.setGenerationBatchHash("b".repeat(64));
        prediction.setPredictionVersion(1);
        prediction.setAsianOddsSnapshotId(9_901L);
        prediction.setAsianHandicapPick(AsianHandicapPickEnum.AWAY_COVER);
        prediction.setAsianHandicapConfidenceLevel(ConfidenceLevelEnum.MEDIUM);
        prediction.setTotalGoalsPick(TotalGoalsPickEnum.UNDER);
        prediction.setTotalGoalsConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("客队受让与小球的模型概率高于市场");
        prediction.setGeneratedAt(Instant.parse("2026-10-21T07:30:00.654321Z"));
        return prediction;
    }

    private void populateModelProbabilities(Prediction prediction) {
        prediction.setLambdaHome(new BigDecimal("1.420000"));
        prediction.setLambdaAway(new BigDecimal("1.180000"));
        prediction.setModelCoverProb(new BigDecimal("0.530000"));
        prediction.setMarketCoverProb(new BigDecimal("0.510000"));
        prediction.setEdgeCover(new BigDecimal("0.020000"));
        prediction.setModelOverProb(new BigDecimal("0.490000"));
        prediction.setMarketOverProb(new BigDecimal("0.500000"));
        prediction.setEdgeOver(new BigDecimal("-0.010000"));
        prediction.setHandicapDispersion(new BigDecimal("0.013000"));
        prediction.setTotalsDispersion(new BigDecimal("0.021000"));
    }

    private Prediction prediction() {
        Prediction prediction = new Prediction();
        prediction.setId(303_001L);
        prediction.setMatchId(303_101L);
        prediction.setModelVersion("model-v1");
        prediction.setFeatureVersion("feature-v1");
        prediction.setGenerationBatchId("t303-batch-1");
        prediction.setGenerationBatchHash("a".repeat(64));
        prediction.setPredictionVersion(1);
        prediction.setHomeWinProb(new BigDecimal("0.450000"));
        prediction.setDrawProb(new BigDecimal("0.300000"));
        prediction.setAwayWinProb(new BigDecimal("0.250000"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("主队近期状态稳定");
        prediction.setGeneratedAt(Instant.parse("2026-07-26T07:30:00.654321Z"));
        return prediction;
    }
}
