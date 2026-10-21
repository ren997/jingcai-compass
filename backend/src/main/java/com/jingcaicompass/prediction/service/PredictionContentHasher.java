package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
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

    public static final int LEGACY_HASH_SCHEMA_VERSION = 1;
    public static final int HASH_SCHEMA_VERSION = 2;
    public static final int ASIAN_HASH_SCHEMA_VERSION = 3;
    /** 亚盘十列模型概率进入规范化内容后的结构版本；v1~v3 永久保留以复算存量行。 */
    public static final int ASIAN_PROBABILITY_HASH_SCHEMA_VERSION = 4;
    private static final int PROBABILITY_SCALE = 6;
    private static final int MODEL_PROBABILITY_SCALE = 6;
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

        if (effectivePredictionType(prediction) == PredictionTypeEnum.ASIAN) {
            return sha256Asian(prediction, normalizedPublishTime, normalizedLockTime);
        }
        BigDecimal home = normalizeProbability(prediction.getHomeWinProb(), "homeWinProb");
        BigDecimal draw = normalizeProbability(prediction.getDrawProb(), "drawProb");
        BigDecimal away = normalizeProbability(prediction.getAwayWinProb(), "awayWinProb");
        BigDecimal sum = home.add(draw).add(away);
        if (sum.compareTo(MIN_PROBABILITY_SUM) < 0 || sum.compareTo(MAX_PROBABILITY_SUM) > 0) {
            throw new IllegalArgumentException("probability sum must be within 1 +/- 0.000001");
        }

        CanonicalPredictionV1 base = new CanonicalPredictionV1(
                LEGACY_HASH_SCHEMA_VERSION,
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

        if (!hasAsianMarketPrediction(prediction)) {
            return sha256Hex(base);
        }

        CanonicalPredictionV2 canonical = new CanonicalPredictionV2(
                HASH_SCHEMA_VERSION,
                base.predictionId(),
                base.matchId(),
                base.modelVersion(),
                base.featureVersion(),
                base.generationBatchId(),
                base.generationBatchHash(),
                base.predictionVersion(),
                base.homeWinProb(),
                base.drawProb(),
                base.awayWinProb(),
                base.handicapPick(),
                base.expectedTotalGoals(),
                requirePositive(prediction.getAsianOddsSnapshotId(), "asianOddsSnapshotId"),
                prediction.getAsianHandicapPick().getCode(),
                prediction.getTotalGoalsPick().getCode(),
                base.confidenceLevel(),
                base.analysisSummary(),
                base.generatedAt(),
                base.publishTime(),
                base.lockTime()
        );
        return sha256Hex(canonical);
    }

    /**
     * 返回当前预测内容所使用的规范化哈希结构版本。
     *
     * <p>亚盘按十列模型概率是否落库区分 v3 / v4：十列是发布后被触发器冻结的公开内容，
     * 若不进哈希，「公开可验证」就只能覆盖方向与置信度，算不出模型当时给出的概率。
     */
    public int hashSchemaVersion(Prediction prediction) {
        Objects.requireNonNull(prediction, "prediction must not be null");
        if (effectivePredictionType(prediction) == PredictionTypeEnum.ASIAN) {
            return hasAsianModelProbabilities(prediction)
                    ? ASIAN_PROBABILITY_HASH_SCHEMA_VERSION
                    : ASIAN_HASH_SCHEMA_VERSION;
        }
        return hasAsianMarketPrediction(prediction) ? HASH_SCHEMA_VERSION : LEGACY_HASH_SCHEMA_VERSION;
    }

    /**
     * 校验已落库的哈希并返回实际生效的结构版本。
     *
     * <p>V24 上线到本结构上线之间存在「十列已填、哈希仍按 v3 计算」的存量行。发布侧不重算这些行，
     * 因此复算必须回落到 v3，否则快照生成会以「哈希不匹配」整批失败——那是比不覆盖概率更坏的结局。
     */
    public ResolvedPredictionHash verifyStoredHash(
            Prediction prediction,
            Instant publishTime,
            Instant lockTime
    ) {
        Objects.requireNonNull(prediction, "prediction must not be null");
        String stored = requireSha256(prediction.getPredictionHash(), "predictionHash");
        // 1) 先按当前结构复算
        int version = hashSchemaVersion(prediction);
        if (stored.equals(sha256Hex(prediction, publishTime, lockTime))) {
            return new ResolvedPredictionHash(version, stored);
        }

        // 2) 亚盘 v4 行可能仍是 v3 时期的哈希，回落到 v3 规范化内容再比一次
        if (version == ASIAN_PROBABILITY_HASH_SCHEMA_VERSION
                && stored.equals(sha256Asian(prediction, publishTime, lockTime, false))) {
            return new ResolvedPredictionHash(ASIAN_HASH_SCHEMA_VERSION, stored);
        }
        throw new IllegalArgumentException("prediction hash mismatch: " + prediction.getId());
    }

    private String sha256Asian(Prediction prediction, Instant publishTime, Instant lockTime) {
        return sha256Hex(canonicalAsian(
                prediction,
                publishTime,
                lockTime,
                hasAsianModelProbabilities(prediction)
        ));
    }

    private String sha256Asian(
            Prediction prediction,
            Instant publishTime,
            Instant lockTime,
            boolean withModelProbabilities
    ) {
        return sha256Hex(canonicalAsian(prediction, publishTime, lockTime, withModelProbabilities));
    }

    private Object canonicalAsian(
            Prediction prediction,
            Instant publishTime,
            Instant lockTime,
            boolean withModelProbabilities
    ) {
        if (prediction.getHomeWinProb() != null || prediction.getDrawProb() != null
                || prediction.getAwayWinProb() != null || prediction.getHandicapPick() != null
                || prediction.getExpectedTotalGoals() != null || prediction.getConfidenceLevel() != null) {
            throw new IllegalArgumentException("Asian prediction must not contain Sporttery fields");
        }
        boolean hasHandicap = prediction.getAsianHandicapPick() != null;
        boolean hasTotals = prediction.getTotalGoalsPick() != null;
        if (prediction.getAsianOddsSnapshotId() == null || (!hasHandicap && !hasTotals)
                || (hasHandicap != (prediction.getAsianHandicapConfidenceLevel() != null))
                || (hasTotals != (prediction.getTotalGoalsConfidenceLevel() != null))) {
            throw new IllegalArgumentException("Asian prediction direction and confidence fields are inconsistent");
        }
        CanonicalPredictionV3 base = new CanonicalPredictionV3(
                ASIAN_HASH_SCHEMA_VERSION,
                requirePositive(prediction.getId(), "predictionId"),
                requirePositive(prediction.getMatchId(), "matchId"),
                requireText(prediction.getModelVersion(), "modelVersion"),
                requireText(prediction.getFeatureVersion(), "featureVersion"),
                requireText(prediction.getGenerationBatchId(), "generationBatchId"),
                requireSha256(prediction.getGenerationBatchHash(), "generationBatchHash"),
                requirePositive(prediction.getPredictionVersion(), "predictionVersion"),
                requirePositive(prediction.getAsianOddsSnapshotId(), "asianOddsSnapshotId"),
                hasHandicap ? prediction.getAsianHandicapPick().getCode() : null,
                hasHandicap ? prediction.getAsianHandicapConfidenceLevel().getCode() : null,
                hasTotals ? prediction.getTotalGoalsPick().getCode() : null,
                hasTotals ? prediction.getTotalGoalsConfidenceLevel().getCode() : null,
                requireText(prediction.getAnalysisSummary(), "analysisSummary"),
                formatInstant(requireInstant(prediction.getGeneratedAt(), "generatedAt")),
                formatInstant(publishTime),
                formatInstant(lockTime)
        );
        if (!withModelProbabilities) {
            return base;
        }
        return new CanonicalPredictionV4(
                ASIAN_PROBABILITY_HASH_SCHEMA_VERSION,
                base.predictionId(),
                base.matchId(),
                base.modelVersion(),
                base.featureVersion(),
                base.generationBatchId(),
                base.generationBatchHash(),
                base.predictionVersion(),
                base.asianOddsSnapshotId(),
                base.asianHandicapPick(),
                base.asianHandicapConfidenceLevel(),
                base.totalGoalsPick(),
                base.totalGoalsConfidenceLevel(),
                requirePositiveLambda(prediction.getLambdaHome(), "lambdaHome"),
                requirePositiveLambda(prediction.getLambdaAway(), "lambdaAway"),
                requireProbability(prediction.getModelCoverProb(), "modelCoverProb"),
                requireProbability(prediction.getMarketCoverProb(), "marketCoverProb"),
                requireEdge(prediction.getEdgeCover(), "edgeCover"),
                requireProbability(prediction.getModelOverProb(), "modelOverProb"),
                requireProbability(prediction.getMarketOverProb(), "marketOverProb"),
                requireEdge(prediction.getEdgeOver(), "edgeOver"),
                requireNonNegative(prediction.getHandicapDispersion(), "handicapDispersion"),
                requireNonNegative(prediction.getTotalsDispersion(), "totalsDispersion"),
                base.analysisSummary(),
                base.generatedAt(),
                base.publishTime(),
                base.lockTime()
        );
    }

    private PredictionTypeEnum effectivePredictionType(Prediction prediction) {
        return prediction.getPredictionType() == null ? PredictionTypeEnum.SPORTTERY : prediction.getPredictionType();
    }

    private String sha256Hex(Object canonical) {
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

    private boolean hasAsianMarketPrediction(Prediction prediction) {
        boolean hasSnapshot = prediction.getAsianOddsSnapshotId() != null;
        boolean hasHandicapPick = prediction.getAsianHandicapPick() != null;
        boolean hasTotalGoalsPick = prediction.getTotalGoalsPick() != null;
        if (!hasSnapshot && !hasHandicapPick && !hasTotalGoalsPick) {
            return false;
        }
        if (!hasSnapshot || !hasHandicapPick || !hasTotalGoalsPick) {
            throw new IllegalArgumentException("Asian market prediction fields must be all present or all absent");
        }
        return true;
    }

    /**
     * 判断亚盘十列模型概率是否落库。
     *
     * <p>只允许「十列全有」或「十列全无」。部分落库的行既不能按 v3 也不能按 v4 复算，
     * 一旦放过就会让公开快照的摘要与库内哈希长期不一致，且没有任何告警。
     */
    private boolean hasAsianModelProbabilities(Prediction prediction) {
        BigDecimal[] columns = {
            prediction.getLambdaHome(),
            prediction.getLambdaAway(),
            prediction.getModelCoverProb(),
            prediction.getMarketCoverProb(),
            prediction.getEdgeCover(),
            prediction.getModelOverProb(),
            prediction.getMarketOverProb(),
            prediction.getEdgeOver(),
            prediction.getHandicapDispersion(),
            prediction.getTotalsDispersion()
        };
        int present = 0;
        for (BigDecimal column : columns) {
            if (column != null) {
                present++;
            }
        }
        if (present == 0) {
            return false;
        }
        if (present != columns.length) {
            throw new IllegalArgumentException(
                    "asian model probability columns must be all present or all absent"
            );
        }
        return true;
    }

    private BigDecimal requireProbability(BigDecimal value, String field) {
        BigDecimal normalized = requireDecimal(value, field, MODEL_PROBABILITY_SCALE);
        if (normalized.compareTo(BigDecimal.ZERO) < 0 || normalized.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return normalized;
    }

    /** edge 是模型概率减市场概率，取值在 [-1, 1]，符号有意义不能取绝对值。 */
    private BigDecimal requireEdge(BigDecimal value, String field) {
        BigDecimal normalized = requireDecimal(value, field, MODEL_PROBABILITY_SCALE);
        if (normalized.compareTo(BigDecimal.ONE.negate()) < 0 || normalized.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between -1 and 1");
        }
        return normalized;
    }

    private BigDecimal requirePositiveLambda(BigDecimal value, String field) {
        BigDecimal normalized = requireDecimal(value, field, MODEL_PROBABILITY_SCALE);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return normalized;
    }

    private BigDecimal requireNonNegative(BigDecimal value, String field) {
        BigDecimal normalized = requireDecimal(value, field, MODEL_PROBABILITY_SCALE);
        if (normalized.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return normalized;
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
    private record CanonicalPredictionV1(
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
            "asianOddsSnapshotId",
            "asianHandicapPick",
            "totalGoalsPick",
            "confidenceLevel",
            "analysisSummary",
            "generatedAt",
            "publishTime",
            "lockTime"
    })
    private record CanonicalPredictionV2(
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
            Long asianOddsSnapshotId,
            String asianHandicapPick,
            String totalGoalsPick,
            String confidenceLevel,
            String analysisSummary,
            String generatedAt,
            String publishTime,
            String lockTime
    ) {
    }

    @JsonPropertyOrder({
            "hashSchemaVersion", "predictionId", "matchId", "modelVersion", "featureVersion",
            "generationBatchId", "generationBatchHash", "predictionVersion", "asianOddsSnapshotId",
            "asianHandicapPick", "asianHandicapConfidenceLevel", "totalGoalsPick",
            "totalGoalsConfidenceLevel", "analysisSummary", "generatedAt", "publishTime", "lockTime"
    })
    private record CanonicalPredictionV3(
            int hashSchemaVersion,
            Long predictionId,
            Long matchId,
            String modelVersion,
            String featureVersion,
            String generationBatchId,
            String generationBatchHash,
            Integer predictionVersion,
            Long asianOddsSnapshotId,
            String asianHandicapPick,
            String asianHandicapConfidenceLevel,
            String totalGoalsPick,
            String totalGoalsConfidenceLevel,
            String analysisSummary,
            String generatedAt,
            String publishTime,
            String lockTime
    ) {
    }

    /**
     * V4：V3 字段顺序原样保留，在方向与置信度之后插入十列模型概率。
     *
     * <p>V3 不能改动——已发布行的 `prediction_hash` 被触发器冻结，字段顺序或精度一变，
     * 存量行就再也复算不出自己的哈希。十列精度统一 6 位，与 `numeric(9,6)` 的落库口径一致；
     * 用 4 位或 5 位会静默四舍五入，让快照复算结果与库内数据出现无法解释的偏差。
     */
    @JsonPropertyOrder({
            "hashSchemaVersion", "predictionId", "matchId", "modelVersion", "featureVersion",
            "generationBatchId", "generationBatchHash", "predictionVersion", "asianOddsSnapshotId",
            "asianHandicapPick", "asianHandicapConfidenceLevel", "totalGoalsPick",
            "totalGoalsConfidenceLevel", "lambdaHome", "lambdaAway", "modelCoverProb",
            "marketCoverProb", "edgeCover", "modelOverProb", "marketOverProb", "edgeOver",
            "handicapDispersion", "totalsDispersion", "analysisSummary", "generatedAt",
            "publishTime", "lockTime"
    })
    private record CanonicalPredictionV4(
            int hashSchemaVersion,
            Long predictionId,
            Long matchId,
            String modelVersion,
            String featureVersion,
            String generationBatchId,
            String generationBatchHash,
            Integer predictionVersion,
            Long asianOddsSnapshotId,
            String asianHandicapPick,
            String asianHandicapConfidenceLevel,
            String totalGoalsPick,
            String totalGoalsConfidenceLevel,
            BigDecimal lambdaHome,
            BigDecimal lambdaAway,
            BigDecimal modelCoverProb,
            BigDecimal marketCoverProb,
            BigDecimal edgeCover,
            BigDecimal modelOverProb,
            BigDecimal marketOverProb,
            BigDecimal edgeOver,
            BigDecimal handicapDispersion,
            BigDecimal totalsDispersion,
            String analysisSummary,
            String generatedAt,
            String publishTime,
            String lockTime
    ) {
    }

    /** 已落库哈希的复算结果：实际生效的哈希与其结构版本。 */
    public record ResolvedPredictionHash(int schemaVersion, String hash) {
    }
}
