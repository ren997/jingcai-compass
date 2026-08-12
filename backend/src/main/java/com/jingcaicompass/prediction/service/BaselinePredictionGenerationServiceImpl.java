package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.prediction.dto.PredictionImportDto;
import com.jingcaicompass.prediction.dto.PredictionImportFileDto;
import com.jingcaicompass.prediction.dto.PredictionImportResultDto;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
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

/**
 * 使用已落库的体彩 SP 与已确认亚盘生成可复现的首版离线预测，并复用既有 DRAFT 导入流程。
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BaselinePredictionGenerationServiceImpl implements BaselinePredictionGenerationService {

    static final String MODEL_VERSION = "t306-odds-baseline-v2";
    static final String FEATURE_VERSION = "t306-sporttery-asian-v2";
    private static final Logger LOG = LoggerFactory.getLogger(BaselinePredictionGenerationServiceImpl.class);
    private static final int PROBABILITY_SCALE = 6;
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal TOTALS_ADJUSTMENT = new BigDecimal("0.50");
    private static final BigDecimal ASIAN_ODDS_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal SPORTTERY_ODDS_WEIGHT = BigDecimal.ONE.subtract(ASIAN_ODDS_WEIGHT);
    private static final BigDecimal HANDICAP_LINE_WEIGHT = new BigDecimal("0.03");
    private static final BigDecimal MAX_HANDICAP_SHIFT = new BigDecimal("0.10");
    private static final BigDecimal MIN_HOME_AWAY_SHARE = new BigDecimal("0.05");
    private static final BigDecimal MAX_HOME_AWAY_SHARE = new BigDecimal("0.95");
    private static final BigDecimal MEDIUM_CONFIDENCE_MARGIN = new BigDecimal("0.1600");

    private final MatchMapper matchMapper;
    private final SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final PredictionMapper predictionMapper;
    private final PredictionImportService predictionImportService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public BaselinePredictionGenerationServiceImpl(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            PredictionMapper predictionMapper,
            PredictionImportService predictionImportService,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.matchMapper = matchMapper;
        this.sportteryPoolSnapshotMapper = sportteryPoolSnapshotMapper;
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

        // 1) 读取指定业务日的持久化比赛和两侧最新可用快照，全程不调用外部 Provider
        List<MatchEntity> matches = matchMapper.selectPublicDailyMatches(lotteryDate);
        Map<Long, SportteryPoolSnapshot> sportteryByMatchId = latestSportteryByMatchId(matches);
        Map<Long, AsianOddsSnapshot> asianByMatchId = latestConfirmedAsianByMatchId(matches);

        // 2) 按固定输入门槛组装特征；缺失或未确认数据必须归类跳过
        Instant now = clock.instant();
        List<FeatureSet> featureSets = new ArrayList<>();
        Map<BaselinePredictionSkipReasonEnum, Integer> skipped =
                new EnumMap<>(BaselinePredictionSkipReasonEnum.class);
        for (MatchEntity match : matches) {
            FeatureSet featureSet = selectFeatureSet(match, sportteryByMatchId, asianByMatchId, now, skipped);
            if (featureSet != null) {
                featureSets.add(featureSet);
            }
        }

        // 3) 将固定算法输出为严格 JSON，并交给 T302 导入服务写入 DRAFT
        if (featureSets.isEmpty()) {
            recordMetrics(matches.size(), 0, skipped);
            logResult(lotteryDate, operatorUsername, matches.size(), 0, null, skipped, "EMPTY");
            return new BaselinePredictionGenerationVo(
                    lotteryDate,
                    MODEL_VERSION,
                    FEATURE_VERSION,
                    matches.size(),
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    skipped
            );
        }

        featureSets.sort(Comparator.comparing(FeatureSet::matchId));
        Instant generatedAt = featureSets.stream()
                .map(FeatureSet::featureAsOf)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<PredictionImportDto> predictions = featureSets.stream()
                .map(featureSet -> toPrediction(featureSet, generatedAt))
                .toList();
        String batchId = batchId(lotteryDate, predictions);
        byte[] fileContent = serialize(batchId, predictions);

        // 4) 按稳定批次串行化并发生成，后到请求会复用先到请求的导入结果
        predictionMapper.lockGenerationBatch(batchId);
        PredictionImportResultDto imported = predictionImportService.importFile(fileContent);

        // 5) 记录低基数运行指标与摘要日志；发布、锁定和快照仍由既有流程控制
        recordMetrics(matches.size(), predictions.size(), skipped);
        logResult(lotteryDate, operatorUsername, matches.size(), predictions.size(), batchId, skipped, "SUCCESS");
        return new BaselinePredictionGenerationVo(
                lotteryDate,
                MODEL_VERSION,
                FEATURE_VERSION,
                matches.size(),
                predictions.size(),
                imported.insertedCount(),
                imported.reusedCount(),
                imported.generationBatchId(),
                imported.generationBatchHash(),
                generatedAt,
                skipped
        );
    }

    private Map<Long, SportteryPoolSnapshot> latestSportteryByMatchId(List<MatchEntity> matches) {
        List<Long> matchIds = validMatchIds(matches);
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SportteryPoolSnapshot> values = new LinkedHashMap<>();
        for (SportteryPoolSnapshot snapshot : sportteryPoolSnapshotMapper.selectLatestByMatchIds(matchIds)) {
            if (snapshot != null && snapshot.getMatchId() != null) {
                values.put(snapshot.getMatchId(), snapshot);
            }
        }
        return values;
    }

    private Map<Long, AsianOddsSnapshot> latestConfirmedAsianByMatchId(List<MatchEntity> matches) {
        List<Long> matchIds = validMatchIds(matches);
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AsianOddsSnapshot> values = new LinkedHashMap<>();
        for (AsianOddsSnapshot snapshot : asianOddsSnapshotMapper.selectLatestCompleteConfirmedLinesByMatchIds(matchIds)) {
            if (snapshot != null && snapshot.getMatchId() != null) {
                values.put(snapshot.getMatchId(), snapshot);
            }
        }
        return values;
    }

    private List<Long> validMatchIds(List<MatchEntity> matches) {
        return matches.stream()
                .filter(Objects::nonNull)
                .map(MatchEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private FeatureSet selectFeatureSet(
            MatchEntity match,
            Map<Long, SportteryPoolSnapshot> sportteryByMatchId,
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

        SportteryPoolSnapshot sporttery = sportteryByMatchId.get(match.getId());
        if (!isSportteryComplete(sporttery)) {
            skip(skipped, BaselinePredictionSkipReasonEnum.MISSING_SPORTTERY_MARKET);
            return null;
        }
        if (!allPositive(
                sporttery.getHadHomeSp(), sporttery.getHadDrawSp(), sporttery.getHadAwaySp(),
                sporttery.getHhadHomeSp(), sporttery.getHhadDrawSp(), sporttery.getHhadAwaySp()
        )) {
            skip(skipped, BaselinePredictionSkipReasonEnum.INVALID_SPORTTERY_MARKET);
            return null;
        }

        AsianOddsSnapshot asian = asianByMatchId.get(match.getId());
        if (asian == null) {
            skip(skipped, BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET);
            return null;
        }
        if (!allPositive(asian.getHomeOdds(), asian.getAwayOdds(), asian.getOverOdds(), asian.getUnderOdds())) {
            skip(skipped, BaselinePredictionSkipReasonEnum.INVALID_ASIAN_MARKET);
            return null;
        }

        Instant featureAsOf = latest(sporttery.getCapturedAt(), asian.getCapturedAt());
        return new FeatureSet(match, sporttery, asian, featureAsOf);
    }

    private boolean isSportteryComplete(SportteryPoolSnapshot snapshot) {
        return snapshot != null
                && snapshot.getOfficialHandicap() != null
                && snapshot.getHadHomeSp() != null
                && snapshot.getHadDrawSp() != null
                && snapshot.getHadAwaySp() != null
                && snapshot.getHhadHomeSp() != null
                && snapshot.getHhadDrawSp() != null
                && snapshot.getHhadAwaySp() != null
                && snapshot.getCapturedAt() != null;
    }

    private PredictionImportDto toPrediction(FeatureSet featureSet, Instant generatedAt) {
        SportteryPoolSnapshot sporttery = featureSet.sporttery();
        AsianOddsSnapshot asian = featureSet.asian();
        ProbabilitySet probabilities = normalizedProbabilities(sporttery, asian);
        HandicapPickEnum handicapPick = pick(
                sporttery.getHhadHomeSp(), sporttery.getHhadDrawSp(), sporttery.getHhadAwaySp()
        );
        AsianHandicapPickEnum asianHandicapPick = asianHandicapPick(asian.getHomeOdds(), asian.getAwayOdds());
        TotalGoalsPickEnum totalGoalsPick = totalGoalsPick(asian.getOverOdds(), asian.getUnderOdds());
        BigDecimal expectedGoals = expectedGoals(asian.getTotalLine(), asian.getOverOdds(), asian.getUnderOdds());
        ConfidenceLevelEnum confidence = confidence(sporttery, asian);
        return new PredictionImportDto(
                featureSet.matchId(),
                MODEL_VERSION,
                FEATURE_VERSION,
                probabilities.home(),
                probabilities.draw(),
                probabilities.away(),
                handicapPick,
                expectedGoals,
                confidence,
                summary(
                        probabilities,
                        sporttery,
                        asian,
                        handicapPick,
                        asianHandicapPick,
                        totalGoalsPick,
                        expectedGoals
                ),
                generatedAt,
                asian.getId(),
                asianHandicapPick,
                totalGoalsPick
        );
    }

    private ProbabilitySet normalizedProbabilities(SportteryPoolSnapshot sporttery, AsianOddsSnapshot asian) {
        BigDecimal sportteryHome = inverse(sporttery.getHadHomeSp());
        BigDecimal sportteryDraw = inverse(sporttery.getHadDrawSp());
        BigDecimal sportteryAway = inverse(sporttery.getHadAwaySp());
        BigDecimal sportteryTotal = sportteryHome.add(sportteryDraw).add(sportteryAway);
        BigDecimal normalizedDraw = sportteryDraw.divide(sportteryTotal, PROBABILITY_SCALE, RoundingMode.DOWN);
        BigDecimal homeAwayMass = BigDecimal.ONE.subtract(normalizedDraw);
        BigDecimal sportteryHomeShare = sportteryHome.divide(
                sportteryHome.add(sportteryAway),
                PROBABILITY_SCALE,
                RoundingMode.HALF_UP
        );
        BigDecimal asianHomeShare = inverse(asian.getHomeOdds()).divide(
                inverse(asian.getHomeOdds()).add(inverse(asian.getAwayOdds())),
                PROBABILITY_SCALE,
                RoundingMode.HALF_UP
        );
        BigDecimal handicapShift = asian.getHandicapLine().negate().multiply(HANDICAP_LINE_WEIGHT)
                .max(MAX_HANDICAP_SHIFT.negate())
                .min(MAX_HANDICAP_SHIFT);
        BigDecimal combinedHomeShare = sportteryHomeShare.multiply(SPORTTERY_ODDS_WEIGHT)
                .add(asianHomeShare.multiply(ASIAN_ODDS_WEIGHT))
                .add(handicapShift)
                .max(MIN_HOME_AWAY_SHARE)
                .min(MAX_HOME_AWAY_SHARE);
        BigDecimal normalizedHome = homeAwayMass.multiply(combinedHomeShare)
                .setScale(PROBABILITY_SCALE, RoundingMode.DOWN);
        BigDecimal normalizedAway = BigDecimal.ONE.subtract(normalizedHome).subtract(normalizedDraw)
                .setScale(PROBABILITY_SCALE, RoundingMode.UNNECESSARY);
        return new ProbabilitySet(normalizedHome, normalizedDraw, normalizedAway);
    }

    private HandicapPickEnum pick(BigDecimal homeSp, BigDecimal drawSp, BigDecimal awaySp) {
        if (homeSp.compareTo(drawSp) <= 0 && homeSp.compareTo(awaySp) <= 0) {
            return HandicapPickEnum.HOME_WIN;
        }
        if (drawSp.compareTo(awaySp) <= 0) {
            return HandicapPickEnum.DRAW;
        }
        return HandicapPickEnum.AWAY_WIN;
    }

    private AsianHandicapPickEnum asianHandicapPick(BigDecimal homeOdds, BigDecimal awayOdds) {
        return homeOdds.compareTo(awayOdds) <= 0
                ? AsianHandicapPickEnum.HOME_COVER
                : AsianHandicapPickEnum.AWAY_COVER;
    }

    private TotalGoalsPickEnum totalGoalsPick(BigDecimal overOdds, BigDecimal underOdds) {
        return overOdds.compareTo(underOdds) <= 0
                ? TotalGoalsPickEnum.OVER
                : TotalGoalsPickEnum.UNDER;
    }

    private BigDecimal expectedGoals(BigDecimal totalLine, BigDecimal overOdds, BigDecimal underOdds) {
        BigDecimal overImplied = inverse(overOdds);
        BigDecimal underImplied = inverse(underOdds);
        BigDecimal overProbability = overImplied.divide(
                overImplied.add(underImplied),
                PROBABILITY_SCALE,
                RoundingMode.HALF_UP
        );
        return totalLine.add(overProbability.subtract(HALF).multiply(TOTALS_ADJUSTMENT))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private ConfidenceLevelEnum confidence(SportteryPoolSnapshot sporttery, AsianOddsSnapshot asian) {
        BigDecimal hadMargin = inverse(sporttery.getHadHomeSp())
                .add(inverse(sporttery.getHadDrawSp()))
                .add(inverse(sporttery.getHadAwaySp()))
                .subtract(BigDecimal.ONE);
        BigDecimal totalsMargin = inverse(asian.getOverOdds())
                .add(inverse(asian.getUnderOdds()))
                .subtract(BigDecimal.ONE);
        return hadMargin.compareTo(MEDIUM_CONFIDENCE_MARGIN) <= 0
                && totalsMargin.compareTo(MEDIUM_CONFIDENCE_MARGIN) <= 0
                ? ConfidenceLevelEnum.MEDIUM
                : ConfidenceLevelEnum.LOW;
    }

    private String summary(
            ProbabilitySet probabilities,
            SportteryPoolSnapshot sporttery,
            AsianOddsSnapshot asian,
            HandicapPickEnum handicapPick,
            AsianHandicapPickEnum asianHandicapPick,
            TotalGoalsPickEnum totalGoalsPick,
            BigDecimal expectedGoals
    ) {
        return "可解释基线：体彩胜平负 SP 归一化为主胜 "
                + probabilities.home().toPlainString()
                + "、平局 " + probabilities.draw().toPlainString()
                + "、客胜 " + probabilities.away().toPlainString()
                + "；官方让球 " + sporttery.getOfficialHandicap().toPlainString()
                + " 的竞彩让球胜平负（主队 " + sporttery.getOfficialHandicap().toPlainString()
                + "）倾向为 " + handicapPick.getDesc()
                + "；亚盘快照 #" + asian.getId()
                + "（" + asian.getBookmakerCode() + " / " + asian.getProviderCode() + "）让球 "
                + asian.getHandicapLine().toPlainString() + " 为 " + asianHandicapPick.getDesc()
                + "，大小球 " + asian.getTotalLine().toPlainString() + " 为 " + totalGoalsPick.getDesc()
                + "；预期总进球 " + expectedGoals.toPlainString()
                + "。以上亚盘方向为可解释赔率基线，不代表独立校准概率或正期望。";
    }

    private String batchId(LocalDate lotteryDate, List<PredictionImportDto> predictions) {
        String fingerprint = predictions.stream()
                .map(this::predictionFingerprint)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        return "t306-baseline-" + lotteryDate + "-" + sha256(fingerprint).substring(0, 16);
    }

    private String predictionFingerprint(PredictionImportDto prediction) {
        return prediction.matchId()
                + "|" + prediction.modelVersion()
                + "|" + prediction.featureVersion()
                + "|" + prediction.homeWinProb().toPlainString()
                + "|" + prediction.drawProb().toPlainString()
                + "|" + prediction.awayWinProb().toPlainString()
                + "|" + prediction.handicapPick().getCode()
                + "|" + prediction.expectedTotalGoals().toPlainString()
                + "|" + prediction.asianOddsSnapshotId()
                + "|" + prediction.asianHandicapPick().getCode()
                + "|" + prediction.totalGoalsPick().getCode()
                + "|" + prediction.confidenceLevel().getCode()
                + "|" + prediction.analysisSummary()
                + "|" + prediction.generatedAt();
    }

    private byte[] serialize(String batchId, List<PredictionImportDto> predictions) {
        try {
            return objectMapper.writeValueAsBytes(new PredictionImportFileDto(batchId, predictions));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "baseline prediction serialization failed", exception);
        }
    }

    private void recordMetrics(
            int candidateCount,
            int generatedCount,
            Map<BaselinePredictionSkipReasonEnum, Integer> skipped
    ) {
        increment("CANDIDATE", "NONE", candidateCount);
        increment("GENERATED", "NONE", generatedCount);
        skipped.forEach((reason, count) -> increment("SKIPPED", reason.getCode(), count));
    }

    private void increment(String outcome, String reason, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("jingcai.prediction.baseline.matches")
                .tags("outcome", outcome, "reason", reason)
                .register(meterRegistry)
                .increment(count);
    }

    private void logResult(
            LocalDate lotteryDate,
            String operatorUsername,
            int candidateCount,
            int generatedCount,
            String batchId,
            Map<BaselinePredictionSkipReasonEnum, Integer> skipped,
            String status
    ) {
        LOG.info(
                "baseline prediction generation status={}, lotteryDate={}, operator={}, candidates={}, generated={}, batchId={}, skipped={}",
                status,
                lotteryDate,
                StringUtils.hasText(operatorUsername) ? operatorUsername.trim() : "system",
                candidateCount,
                generatedCount,
                batchId,
                skipped
        );
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

    private Instant latest(Instant left, Instant right) {
        if (left == null || right == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "baseline feature capture time is missing");
        }
        return left.isAfter(right) ? left.truncatedTo(ChronoUnit.MICROS) : right.truncatedTo(ChronoUnit.MICROS);
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private record FeatureSet(
            MatchEntity match,
            SportteryPoolSnapshot sporttery,
            AsianOddsSnapshot asian,
            Instant featureAsOf
    ) {
        Long matchId() {
            return match.getId();
        }
    }

    private record ProbabilitySet(BigDecimal home, BigDecimal draw, BigDecimal away) {
    }
}
