package com.jingcaicompass.snapshot.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
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

    public static final int LEGACY_MANIFEST_SCHEMA_VERSION = 1;
    public static final int MANIFEST_SCHEMA_VERSION = 2;
    public static final int ASIAN_MANIFEST_SCHEMA_VERSION = 3;
    /** 亚盘十列模型概率进入 manifest 后的版本；v1~v3 快照永久保留可复算。 */
    public static final int ASIAN_PROBABILITY_MANIFEST_SCHEMA_VERSION = 4;
    private static final int PROBABILITY_SCALE = 6;
    private static final int MODEL_PROBABILITY_SCALE = 6;
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
                manifestSchemaVersion(items),
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

    /**
     * 取本批条目里最高的结构版本。
     *
     * <p>同批可能混有 v3/v4 条目（十列在 V24 之后才落库）。取最高而不是「有任何 v3 就是 v3」：
     * 否则新条目会被降级标注，摘要复算按错的版本解释字段集合。
     */
    private int manifestSchemaVersion(List<PredictionSnapshotManifestItemDto> items) {
        if (items.stream().anyMatch(item -> item.predictionHashSchemaVersion()
                == PredictionContentHasher.ASIAN_PROBABILITY_HASH_SCHEMA_VERSION)) {
            return ASIAN_PROBABILITY_MANIFEST_SCHEMA_VERSION;
        }
        if (items.stream().anyMatch(item ->
                item.predictionHashSchemaVersion() == PredictionContentHasher.ASIAN_HASH_SCHEMA_VERSION)) {
            return ASIAN_MANIFEST_SCHEMA_VERSION;
        }
        return items.stream().anyMatch(item ->
                item.predictionHashSchemaVersion() == PredictionContentHasher.HASH_SCHEMA_VERSION
        ) ? MANIFEST_SCHEMA_VERSION : LEGACY_MANIFEST_SCHEMA_VERSION;
    }

    private PredictionSnapshotManifestItemDto toManifestItem(Prediction prediction) {
        Prediction source = Objects.requireNonNull(prediction, "prediction must not be null");
        if (source.getPredictionStatus() != PredictionStatusEnum.PUBLISHED
                && source.getPredictionStatus() != PredictionStatusEnum.LOCKED) {
            throw new IllegalArgumentException("manifest prediction must be publicly visible");
        }

        Instant publishTime = requireInstant(source.getPublishTime(), "publishTime");
        Instant lockTime = requireInstant(source.getLockTime(), "lockTime");
        // 复算并核对已落库哈希：存量 v3 亚盘行会回落到 v3 结构，不会因新增十列而整批失败
        PredictionContentHasher.ResolvedPredictionHash resolved =
                predictionContentHasher.verifyStoredHash(source, publishTime, lockTime);
        String storedHash = resolved.hash();

        return new PredictionSnapshotManifestItemDto(
                resolved.schemaVersion(),
                requirePositive(source.getId(), "predictionId"),
                requirePositive(source.getMatchId(), "matchId"),
                requireText(source.getModelVersion(), "modelVersion"),
                requireText(source.getFeatureVersion(), "featureVersion"),
                requireText(source.getGenerationBatchId(), "generationBatchId"),
                requireSha256(source.getGenerationBatchHash(), "generationBatchHash"),
                requirePositive(source.getPredictionVersion(), "predictionVersion"),
                source.getPredictionType() == PredictionTypeEnum.ASIAN ? PredictionTypeEnum.ASIAN.getCode() : null,
                source.getHomeWinProb() == null ? null : normalizeDecimal(source.getHomeWinProb(), "homeWinProb", PROBABILITY_SCALE),
                source.getDrawProb() == null ? null : normalizeDecimal(source.getDrawProb(), "drawProb", PROBABILITY_SCALE),
                source.getAwayWinProb() == null ? null : normalizeDecimal(source.getAwayWinProb(), "awayWinProb", PROBABILITY_SCALE),
                source.getHandicapPick() == null ? null : source.getHandicapPick().getCode(),
                source.getExpectedTotalGoals() == null ? null : normalizeDecimal(source.getExpectedTotalGoals(), "expectedTotalGoals", EXPECTED_GOALS_SCALE),
                source.getAsianOddsSnapshotId(),
                source.getAsianHandicapPick() == null ? null : source.getAsianHandicapPick().getCode(),
                source.getTotalGoalsPick() == null ? null : source.getTotalGoalsPick().getCode(),
                source.getAsianHandicapConfidenceLevel() == null ? null : source.getAsianHandicapConfidenceLevel().getCode(),
                source.getTotalGoalsConfidenceLevel() == null ? null : source.getTotalGoalsConfidenceLevel().getCode(),
                normalizeModelDecimal(source.getLambdaHome(), "lambdaHome"),
                normalizeModelDecimal(source.getLambdaAway(), "lambdaAway"),
                normalizeModelDecimal(source.getModelCoverProb(), "modelCoverProb"),
                normalizeModelDecimal(source.getMarketCoverProb(), "marketCoverProb"),
                normalizeModelDecimal(source.getEdgeCover(), "edgeCover"),
                normalizeModelDecimal(source.getModelOverProb(), "modelOverProb"),
                normalizeModelDecimal(source.getMarketOverProb(), "marketOverProb"),
                normalizeModelDecimal(source.getEdgeOver(), "edgeOver"),
                normalizeModelDecimal(source.getHandicapDispersion(), "handicapDispersion"),
                normalizeModelDecimal(source.getTotalsDispersion(), "totalsDispersion"),
                source.getConfidenceLevel() == null ? null : source.getConfidenceLevel().getCode(),
                requireText(source.getAnalysisSummary(), "analysisSummary"),
                formatInstant(requireInstant(source.getGeneratedAt(), "generatedAt")),
                formatInstant(publishTime),
                formatInstant(lockTime),
                storedHash
        );
    }

    /**
     * 十列模型概率的公开精度统一 6 位，与 `numeric(9,6)` 落库口径一致。
     *
     * <p>空值原样保留：V24 之前的亚盘行没有这些列，manifest 靠 NON_NULL 把字段省掉，
     * 补 0 会让「没有概率」变成「概率为 0」，公开复算会得出相反结论。
     */
    private BigDecimal normalizeModelDecimal(BigDecimal value, String field) {
        return value == null ? null : normalizeDecimal(value, field, MODEL_PROBABILITY_SCALE);
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
