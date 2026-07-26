package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jingcaicompass.prediction.entity.Prediction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 生成可公开复算的预测规范化 JSON 与 SHA-256。 */
@Component
public class PredictionContentHasher {

    static final int HASH_SCHEMA_VERSION = 1;
    private static final int PROBABILITY_SCALE = 6;
    private static final int EXPECTED_GOALS_SCALE = 2;
    private static final BigDecimal MIN_PROBABILITY_SUM = new BigDecimal("0.999999");
    private static final BigDecimal MAX_PROBABILITY_SUM = new BigDecimal("1.000001");
    private static final DateTimeFormatter INSTANT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private final ObjectMapper canonicalObjectMapper;

    public PredictionContentHasher(ObjectMapper objectMapper) {
        this.canonicalObjectMapper = objectMapper.copy()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    }

    /**
     * 对固定字段、顺序、数值精度和 UTC 微秒时间的 JSON 字节计算小写 SHA-256。
     */
    public String sha256Hex(Prediction prediction, Instant publishTime, Instant lockTime) {
        Objects.requireNonNull(prediction, "prediction must not be null");
        Instant normalizedPublishTime = requireInstant(publishTime, "publishTime");
        Instant normalizedLockTime = requireInstant(lockTime, "lockTime");
        if (!normalizedPublishTime.isBefore(normalizedLockTime)) {
            throw new IllegalArgumentException("publishTime must be before lockTime");
        }

        BigDecimal home = normalizeProbability(prediction.getHomeWinProb(), "homeWinProb");
        BigDecimal draw = normalizeProbability(prediction.getDrawProb(), "drawProb");
        BigDecimal away = normalizeProbability(prediction.getAwayWinProb(), "awayWinProb");
        BigDecimal sum = home.add(draw).add(away);
        if (sum.compareTo(MIN_PROBABILITY_SUM) < 0 || sum.compareTo(MAX_PROBABILITY_SUM) > 0) {
            throw new IllegalArgumentException("probability sum must be within 1 +/- 0.000001");
        }

        CanonicalPrediction canonical = new CanonicalPrediction(
                HASH_SCHEMA_VERSION,
                requirePositive(prediction.getId(), "predictionId"),
                requirePositive(prediction.getMatchId(), "matchId"),
                requireText(prediction.getModelVersion(), "modelVersion"),
                requireText(prediction.getFeatureVersion(), "featureVersion"),
                requireText(prediction.getGenerationBatchId(), "generationBatchId"),
                requireSha256(prediction.getGenerationBatchHash(), "generationBatchHash"),
                requirePositive(prediction.getPredictionVersion(), "predictionVersion"),
                home,
                draw,
                away,
                Objects.requireNonNull(prediction.getHandicapPick(), "handicapPick must not be null").getCode(),
                normalizeExpectedGoals(prediction.getExpectedTotalGoals()),
                Objects.requireNonNull(
                        prediction.getConfidenceLevel(),
                        "confidenceLevel must not be null"
                ).getCode(),
                requireText(prediction.getAnalysisSummary(), "analysisSummary"),
                formatInstant(requireInstant(prediction.getGeneratedAt(), "generatedAt")),
                formatInstant(normalizedPublishTime),
                formatInstant(normalizedLockTime)
        );

        try {
            // 1) 使用固定 record 字段顺序生成无缩进 UTF-8 JSON
            byte[] canonicalBytes = canonicalObjectMapper.writeValueAsString(canonical)
                    .getBytes(StandardCharsets.UTF_8);

            // 2) 对规范化字节计算小写 SHA-256
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("prediction canonical JSON serialization failed", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private BigDecimal normalizeProbability(BigDecimal value, String field) {
        BigDecimal normalized = requireDecimal(value, field, PROBABILITY_SCALE);
        if (normalized.compareTo(BigDecimal.ZERO) < 0 || normalized.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return normalized;
    }

    private BigDecimal normalizeExpectedGoals(BigDecimal value) {
        BigDecimal normalized = requireDecimal(value, "expectedTotalGoals", EXPECTED_GOALS_SCALE);
        if (normalized.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("expectedTotalGoals must not be negative");
        }
        return normalized;
    }

    private BigDecimal requireDecimal(BigDecimal value, String field, int scale) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " exceeds supported scale", exception);
        }
    }

    private Long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private Integer requirePositive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private String requireSha256(String value, String field) {
        String normalized = requireText(value, field);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex value");
        }
        return normalized;
    }

    private Instant requireInstant(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private String formatInstant(Instant value) {
        return INSTANT_FORMATTER.format(value);
    }

    @JsonPropertyOrder({
            "hashSchemaVersion",
            "predictionId",
            "matchId",
            "modelVersion",
            "featureVersion",
            "generationBatchId",
            "generationBatchHash",
            "predictionVersion",
            "homeWinProb",
            "drawProb",
            "awayWinProb",
            "handicapPick",
            "expectedTotalGoals",
            "confidenceLevel",
            "analysisSummary",
            "generatedAt",
            "publishTime",
            "lockTime"
    })
    private record CanonicalPrediction(
            int hashSchemaVersion,
            Long predictionId,
            Long matchId,
            String modelVersion,
            String featureVersion,
            String generationBatchId,
            String generationBatchHash,
            Integer predictionVersion,
            BigDecimal homeWinProb,
            BigDecimal drawProb,
            BigDecimal awayWinProb,
            String handicapPick,
            BigDecimal expectedTotalGoals,
            String confidenceLevel,
            String analysisSummary,
            String generatedAt,
            String publishTime,
            String lockTime
    ) {
    }
}
