package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
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
