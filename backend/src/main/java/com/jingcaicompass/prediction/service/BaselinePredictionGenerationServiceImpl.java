package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.prediction.dto.PredictionImportDto;
import com.jingcaicompass.prediction.dto.PredictionImportFileDto;
import com.jingcaicompass.prediction.dto.PredictionImportResultDto;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 只使用已确认亚盘主盘生成可复现的分项方向草稿。 */
@Service
@ConditionalOnBean(DataSource.class)
public class BaselinePredictionGenerationServiceImpl implements BaselinePredictionGenerationService {

    static final String MODEL_VERSION = "t306-asian-baseline-v3";
    static final String FEATURE_VERSION = "t306-asian-mainline-v3";
    private static final Logger LOG = LoggerFactory.getLogger(BaselinePredictionGenerationServiceImpl.class);
    private static final BigDecimal MEDIUM_DIRECTION_SIGNAL = new BigDecimal("0.0250");
    private static final BigDecimal HIGH_DIRECTION_SIGNAL = new BigDecimal("0.0750");
    private static final BigDecimal HIGH_CONFIDENCE_MARGIN = new BigDecimal("0.1000");
    private static final BigDecimal MEDIUM_CONFIDENCE_MARGIN = new BigDecimal("0.1600");

    private final MatchMapper matchMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final PredictionMapper predictionMapper;
    private final PredictionImportService predictionImportService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public BaselinePredictionGenerationServiceImpl(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper unusedSportteryPoolSnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            PredictionMapper predictionMapper,
            PredictionImportService predictionImportService,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.matchMapper = matchMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
        this.predictionMapper = predictionMapper;
        this.predictionImportService = predictionImportService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public BaselinePredictionGenerationVo generateAndImport(LocalDate lotteryDate, String operatorUsername) {
        if (lotteryDate == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "lotteryDate must not be null");
        }

        // 1) 只读取持久化比赛和已确认的完整亚盘主盘，不调用外部 Provider。
        List<MatchEntity> matches = matchMapper.selectPublicDailyMatches(lotteryDate);
        Map<Long, AsianOddsSnapshot> asianByMatchId = latestConfirmedAsianByMatchId(matches);

        // 2) 按比赛状态、开赛时间与亚盘完整性筛选输入。
        Instant now = clock.instant();
        List<FeatureSet> featureSets = new ArrayList<>();
        Map<BaselinePredictionSkipReasonEnum, Integer> skipped =
                new EnumMap<>(BaselinePredictionSkipReasonEnum.class);
        for (MatchEntity match : matches) {
            FeatureSet featureSet = selectFeatureSet(match, asianByMatchId, now, skipped);
            if (featureSet != null) {
                featureSets.add(featureSet);
            }
        }

        // 3) 为两个市场分别判定置信度；两项均低时不生成任何可发布草稿。
        featureSets.sort(Comparator.comparing(FeatureSet::matchId));
        List<PredictionImportDto> predictions = new ArrayList<>();
        for (FeatureSet featureSet : featureSets) {
            PredictionImportDto prediction = toPrediction(featureSet, featureSet.featureAsOf());
            if (prediction == null) {
                skip(skipped, BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS);
            } else {
                predictions.add(prediction);
            }
        }
        if (predictions.isEmpty()) {
            recordMetrics(matches.size(), 0, skipped);
            logResult(lotteryDate, operatorUsername, matches.size(), 0, null, skipped, "EMPTY");
            return emptyResult(lotteryDate, matches.size(), skipped);
        }
        Instant generatedAt = featureSets.stream()
                .map(FeatureSet::featureAsOf)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        // 4) 以确定性批次导入草稿，发布、锁定与快照仍使用既有生命周期。
        String batchId = batchId(lotteryDate, predictions);
        byte[] fileContent = serialize(batchId, predictions);
        predictionMapper.lockGenerationBatch(batchId);
        PredictionImportResultDto imported = predictionImportService.importFile(fileContent);

        // 5) 记录运行摘要；低置信方向不会在该批草稿中出现。
        recordMetrics(matches.size(), predictions.size(), skipped);
        logResult(lotteryDate, operatorUsername, matches.size(), predictions.size(), batchId, skipped, "SUCCESS");
        return new BaselinePredictionGenerationVo(
                lotteryDate, MODEL_VERSION, FEATURE_VERSION, matches.size(), predictions.size(),
                imported.insertedCount(), imported.reusedCount(), imported.generationBatchId(),
                imported.generationBatchHash(), generatedAt, skipped
        );
    }

    private BaselinePredictionGenerationVo emptyResult(
            LocalDate lotteryDate,
            int candidateCount,
            Map<BaselinePredictionSkipReasonEnum, Integer> skipped
    ) {
        return new BaselinePredictionGenerationVo(
                lotteryDate, MODEL_VERSION, FEATURE_VERSION, candidateCount, 0, 0, 0,
                null, null, null, skipped
        );
    }

    private Map<Long, AsianOddsSnapshot> latestConfirmedAsianByMatchId(List<MatchEntity> matches) {
        List<Long> matchIds = matches.stream()
                .filter(Objects::nonNull)
                .map(MatchEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AsianOddsSnapshot> result = new LinkedHashMap<>();
        for (AsianOddsSnapshot snapshot : asianOddsSnapshotMapper.selectLatestCompleteConfirmedLinesByMatchIds(matchIds)) {
            if (snapshot != null && snapshot.getMatchId() != null) {
                result.put(snapshot.getMatchId(), snapshot);
            }
        }
        return result;
    }

    private FeatureSet selectFeatureSet(
            MatchEntity match,
            Map<Long, AsianOddsSnapshot> asianByMatchId,
            Instant now,
            Map<BaselinePredictionSkipReasonEnum, Integer> skipped
    ) {
        if (match == null || match.getId() == null || match.getMatchStatus() != MatchStatusEnum.SCHEDULED) {
            skip(skipped, BaselinePredictionSkipReasonEnum.NOT_SCHEDULED);
            return null;
        }
        if (match.getKickoffTime() == null || !match.getKickoffTime().isAfter(now)) {
            skip(skipped, BaselinePredictionSkipReasonEnum.KICKOFF_PASSED);
            return null;
        }
        AsianOddsSnapshot asian = asianByMatchId.get(match.getId());
        if (asian == null) {
            skip(skipped, BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET);
            return null;
        }
        if (!allPositive(asian.getHomeOdds(), asian.getAwayOdds(), asian.getOverOdds(), asian.getUnderOdds())
                || asian.getCapturedAt() == null) {
            skip(skipped, BaselinePredictionSkipReasonEnum.INVALID_ASIAN_MARKET);
            return null;
        }
        return new FeatureSet(match, asian, asian.getCapturedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private PredictionImportDto toPrediction(FeatureSet featureSet, Instant generatedAt) {
        AsianOddsSnapshot asian = featureSet.asian();
        ConfidenceLevelEnum handicapConfidence = confidence(asian.getHomeOdds(), asian.getAwayOdds());
        ConfidenceLevelEnum totalsConfidence = confidence(asian.getOverOdds(), asian.getUnderOdds());
        AsianHandicapPickEnum handicapPick = handicapConfidence == ConfidenceLevelEnum.LOW
                ? null : asianHandicapPick(asian.getHomeOdds(), asian.getAwayOdds());
        TotalGoalsPickEnum totalsPick = totalsConfidence == ConfidenceLevelEnum.LOW
                ? null : totalGoalsPick(asian.getOverOdds(), asian.getUnderOdds());
        if (handicapPick == null && totalsPick == null) {
            return null;
        }
        return new PredictionImportDto(
                featureSet.matchId(), MODEL_VERSION, FEATURE_VERSION,
                null, null, null, null, null, null,
                summary(asian, handicapPick, handicapConfidence, totalsPick, totalsConfidence), generatedAt,
                asian.getId(), handicapPick, totalsPick,
                handicapPick == null ? null : handicapConfidence,
                totalsPick == null ? null : totalsConfidence,
                PredictionTypeEnum.ASIAN
        );
    }

    private AsianHandicapPickEnum asianHandicapPick(BigDecimal homeOdds, BigDecimal awayOdds) {
        return homeOdds.compareTo(awayOdds) <= 0
                ? AsianHandicapPickEnum.HOME_COVER
                : AsianHandicapPickEnum.AWAY_COVER;
    }

    private TotalGoalsPickEnum totalGoalsPick(BigDecimal overOdds, BigDecimal underOdds) {
        return overOdds.compareTo(underOdds) <= 0 ? TotalGoalsPickEnum.OVER : TotalGoalsPickEnum.UNDER;
    }

    private ConfidenceLevelEnum confidence(BigDecimal primaryOdds, BigDecimal secondaryOdds) {
        BigDecimal primary = inverse(primaryOdds);
        BigDecimal secondary = inverse(secondaryOdds);
        BigDecimal total = primary.add(secondary);
        BigDecimal margin = total.subtract(BigDecimal.ONE);
        BigDecimal signal = primary.subtract(secondary).abs().divide(total, 6, RoundingMode.HALF_UP);
        if (margin.compareTo(HIGH_CONFIDENCE_MARGIN) <= 0 && signal.compareTo(HIGH_DIRECTION_SIGNAL) >= 0) {
            return ConfidenceLevelEnum.HIGH;
        }
        if (margin.compareTo(MEDIUM_CONFIDENCE_MARGIN) <= 0 && signal.compareTo(MEDIUM_DIRECTION_SIGNAL) >= 0) {
            return ConfidenceLevelEnum.MEDIUM;
        }
        return ConfidenceLevelEnum.LOW;
    }

    private String summary(
            AsianOddsSnapshot asian,
            AsianHandicapPickEnum handicapPick,
            ConfidenceLevelEnum handicapConfidence,
            TotalGoalsPickEnum totalsPick,
            ConfidenceLevelEnum totalsConfidence
    ) {
        String handicap = handicapPick == null
                ? "让球方向因低置信度不发布"
                : "让球 " + asian.getHandicapLine().toPlainString() + " 为 "
                        + handicapPick.getDesc() + "（" + handicapConfidence.getDesc() + "）";
        String totals = totalsPick == null
                ? "大小球方向因低置信度不发布"
                : "大小球 " + asian.getTotalLine().toPlainString() + " 为 "
                        + totalsPick.getDesc() + "（" + totalsConfidence.getDesc() + "）";
        return "亚盘专用可解释基线：亚盘快照 #" + asian.getId()
                + "（" + asian.getBookmakerCode() + " / " + asian.getProviderCode() + "）"
                + handicap + "；" + totals
                + "。置信度基于同盘口水位差与水差质量，只作为方向筛选，不代表独立校准概率或正期望。";
    }

    private String batchId(LocalDate lotteryDate, List<PredictionImportDto> predictions) {
        String fingerprint = predictions.stream()
                .map(this::predictionFingerprint)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        return "t306-asian-" + lotteryDate + "-" + sha256(fingerprint).substring(0, 16);
    }

    private String predictionFingerprint(PredictionImportDto prediction) {
        return prediction.matchId() + "|" + prediction.modelVersion() + "|" + prediction.featureVersion()
                + "|" + prediction.asianOddsSnapshotId() + "|" + codeOf(prediction.asianHandicapPick())
                + "|" + codeOf(prediction.totalGoalsPick())
                + "|" + codeOf(prediction.asianHandicapConfidenceLevel())
                + "|" + codeOf(prediction.totalGoalsConfidenceLevel())
                + "|" + prediction.predictionType().getCode()
                + "|" + prediction.analysisSummary() + "|" + prediction.generatedAt();
    }

    private String codeOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof AsianHandicapPickEnum pick) {
            return pick.getCode();
        }
        if (value instanceof TotalGoalsPickEnum pick) {
            return pick.getCode();
        }
        if (value instanceof ConfidenceLevelEnum confidence) {
            return confidence.getCode();
        }
        throw new IllegalArgumentException("unsupported prediction code value: " + value);
    }

    private byte[] serialize(String batchId, List<PredictionImportDto> predictions) {
        try {
            return objectMapper.copy()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsBytes(new PredictionImportFileDto(batchId, predictions));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "baseline prediction serialization failed", exception);
        }
    }

    private void recordMetrics(int candidates, int generated, Map<BaselinePredictionSkipReasonEnum, Integer> skipped) {
        increment("CANDIDATE", "NONE", candidates);
        increment("GENERATED", "NONE", generated);
        skipped.forEach((reason, count) -> increment("SKIPPED", reason.getCode(), count));
    }

    private void increment(String outcome, String reason, int count) {
        if (count > 0) {
            Counter.builder("jingcai.prediction.baseline.matches")
                    .tags("outcome", outcome, "reason", reason).register(meterRegistry).increment(count);
        }
    }

    private void logResult(
            LocalDate lotteryDate, String operatorUsername, int candidateCount, int generatedCount,
            String batchId, Map<BaselinePredictionSkipReasonEnum, Integer> skipped, String status
    ) {
        LOG.info("baseline prediction generation status={}, lotteryDate={}, operator={}, candidates={}, generated={}, batchId={}, skipped={}",
                status, lotteryDate, StringUtils.hasText(operatorUsername) ? operatorUsername.trim() : "system",
                candidateCount, generatedCount, batchId, skipped);
    }

    private void skip(Map<BaselinePredictionSkipReasonEnum, Integer> skipped, BaselinePredictionSkipReasonEnum reason) {
        skipped.merge(reason, 1, Integer::sum);
    }

    private boolean allPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal inverse(BigDecimal value) {
        return BigDecimal.ONE.divide(value, 12, RoundingMode.HALF_UP);
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private record FeatureSet(MatchEntity match, AsianOddsSnapshot asian, Instant featureAsOf) {
        Long matchId() {
            return match.getId();
        }
    }
}
