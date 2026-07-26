package com.jingcaicompass.snapshot.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.service.PredictionContentHasher;
import com.jingcaicompass.snapshot.dto.PredictionSnapshotManifestDto;
import com.jingcaicompass.snapshot.dto.PredictionSnapshotManifestItemDto;
import com.jingcaicompass.snapshot.dto.SnapshotManifestContentDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 校验当前公开预测并生成字段、排序和格式固定的 manifest 字节。 */
@Component
public class SnapshotManifestGenerator {

    public static final int MANIFEST_SCHEMA_VERSION = 1;
    private static final int PROBABILITY_SCALE = 6;
    private static final int EXPECTED_GOALS_SCALE = 2;
    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";
    private static final DateTimeFormatter INSTANT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final Comparator<PredictionSnapshotManifestItemDto> ITEM_ORDER =
            Comparator.comparing(PredictionSnapshotManifestItemDto::matchId)
                    .thenComparing(PredictionSnapshotManifestItemDto::modelVersion)
                    .thenComparing(PredictionSnapshotManifestItemDto::predictionVersion)
                    .thenComparing(PredictionSnapshotManifestItemDto::predictionId);

    private final ObjectMapper canonicalObjectMapper;
    private final PredictionContentHasher predictionContentHasher;

    public SnapshotManifestGenerator(
            ObjectMapper objectMapper,
            PredictionContentHasher predictionContentHasher
    ) {
        this.canonicalObjectMapper = objectMapper.copy()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        this.predictionContentHasher = predictionContentHasher;
    }

    /**
     * 按固定字段、排序、小数和 UTC 微秒格式生成 UTF-8 manifest 及 SHA-256。
     */
    public SnapshotManifestContentDto generate(
            LocalDate snapshotDate,
            List<Prediction> predictions
    ) {
        // 1) 校验业务日，并把每条公开预测转换为可复算的显式 manifest 模型
        LocalDate businessDate = Objects.requireNonNull(
                snapshotDate,
                "snapshotDate must not be null"
        );
        List<PredictionSnapshotManifestItemDto> items = new ArrayList<>();
        Set<PredictionIdentity> identities = new HashSet<>();
        for (Prediction prediction : predictions == null ? List.<Prediction>of() : predictions) {
            PredictionSnapshotManifestItemDto item = toManifestItem(prediction);
            PredictionIdentity identity = new PredictionIdentity(item.matchId(), item.modelVersion());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                        "manifest contains multiple current versions for match/model: "
                                + item.matchId() + "/" + item.modelVersion()
                );
            }
            items.add(item);
        }

        // 2) 使用 JVM 固定字符串比较与数字顺序排序，隔离数据库 collation 差异
        items.sort(ITEM_ORDER);
        PredictionSnapshotManifestDto manifest = new PredictionSnapshotManifestDto(
                MANIFEST_SCHEMA_VERSION,
                businessDate.toString(),
                items.size(),
                List.copyOf(items)
        );

        try {
            // 3) 输出无缩进 UTF-8 JSON，并对最终字节计算小写 SHA-256
            byte[] bytes = canonicalObjectMapper.writeValueAsString(manifest)
                    .getBytes(StandardCharsets.UTF_8);
            return new SnapshotManifestContentDto(bytes, sha256Hex(bytes), items.size());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("snapshot manifest serialization failed", exception);
        }
    }

    private PredictionSnapshotManifestItemDto toManifestItem(Prediction prediction) {
        Prediction source = Objects.requireNonNull(prediction, "prediction must not be null");
        if (source.getPredictionStatus() != PredictionStatusEnum.PUBLISHED
                && source.getPredictionStatus() != PredictionStatusEnum.LOCKED) {
            throw new IllegalArgumentException("manifest prediction must be publicly visible");
        }

        Instant publishTime = requireInstant(source.getPublishTime(), "publishTime");
        Instant lockTime = requireInstant(source.getLockTime(), "lockTime");
        String storedHash = requireSha256(source.getPredictionHash(), "predictionHash");
        String recalculatedHash = predictionContentHasher.sha256Hex(
                source,
                publishTime,
                lockTime
        );
        if (!storedHash.equals(recalculatedHash)) {
            throw new IllegalArgumentException(
                    "prediction hash mismatch: " + source.getId()
            );
        }

        return new PredictionSnapshotManifestItemDto(
                PredictionContentHasher.HASH_SCHEMA_VERSION,
                requirePositive(source.getId(), "predictionId"),
                requirePositive(source.getMatchId(), "matchId"),
                requireText(source.getModelVersion(), "modelVersion"),
                requireText(source.getFeatureVersion(), "featureVersion"),
                requireText(source.getGenerationBatchId(), "generationBatchId"),
                requireSha256(source.getGenerationBatchHash(), "generationBatchHash"),
                requirePositive(source.getPredictionVersion(), "predictionVersion"),
                normalizeDecimal(source.getHomeWinProb(), "homeWinProb", PROBABILITY_SCALE),
                normalizeDecimal(source.getDrawProb(), "drawProb", PROBABILITY_SCALE),
                normalizeDecimal(source.getAwayWinProb(), "awayWinProb", PROBABILITY_SCALE),
                Objects.requireNonNull(
                        source.getHandicapPick(),
                        "handicapPick must not be null"
                ).getCode(),
                normalizeDecimal(
                        source.getExpectedTotalGoals(),
                        "expectedTotalGoals",
                        EXPECTED_GOALS_SCALE
                ),
                Objects.requireNonNull(
                        source.getConfidenceLevel(),
                        "confidenceLevel must not be null"
                ).getCode(),
                requireText(source.getAnalysisSummary(), "analysisSummary"),
                formatInstant(requireInstant(source.getGeneratedAt(), "generatedAt")),
                formatInstant(publishTime),
                formatInstant(lockTime),
                storedHash
        );
    }

    private BigDecimal normalizeDecimal(BigDecimal value, String field, int scale) {
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
        if (!normalized.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase SHA-256 hex value"
            );
        }
        return normalized;
    }

    private Instant requireInstant(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private String formatInstant(Instant value) {
        return INSTANT_FORMATTER.format(value);
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private record PredictionIdentity(Long matchId, String modelVersion) {
    }
}
