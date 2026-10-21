package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianFreezeSnapshotSelector;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.odds.service.AsianMarketMath;
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
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 使用跨庄去水共识主盘生成可复现的分项方向草稿。
 *
 * <p>取数从「每场最新一条完整快照」改为「每场每家每档线的最新报价」：主盘必须由聚合选出，
 * 取数阶段一旦收敛到单条就无法判断哪条线最平衡。结算仍只认冻结行，所以决策线与计账线必然一致。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BaselinePredictionGenerationServiceImpl implements BaselinePredictionGenerationService {

    static final String MODEL_VERSION = "t306-asian-baseline-v4";
    static final String FEATURE_VERSION = "t306-asian-mainline-v4";
    static final String CONSENSUS_METRIC = "jingcai.prediction.asian.consensus";

    private static final Logger LOG = LoggerFactory.getLogger(BaselinePredictionGenerationServiceImpl.class);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal MEDIUM_DIRECTION_SIGNAL = new BigDecimal("0.0250");
    private static final BigDecimal HIGH_DIRECTION_SIGNAL = new BigDecimal("0.0750");
    private static final BigDecimal HIGH_CONFIDENCE_MARGIN = new BigDecimal("0.1000");
    private static final BigDecimal MEDIUM_CONFIDENCE_MARGIN = new BigDecimal("0.1600");
    private static final int SUMMARY_PROBABILITY_SCALE = 4;

    private final MatchMapper matchMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final PredictionMapper predictionMapper;
    private final PredictionImportService predictionImportService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AsianMarketConsensusAggregator consensusAggregator;
    private final AsianFreezeSnapshotSelector freezeSnapshotSelector;
    private final MeterRegistry meterRegistry;

    public BaselinePredictionGenerationServiceImpl(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper unusedSportteryPoolSnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            PredictionMapper predictionMapper,
            PredictionImportService predictionImportService,
            ObjectMapper objectMapper,
            Clock clock,
            AsianOddsAggregationProperties aggregationProperties,
            MeterRegistry meterRegistry
    ) {
        AsianOddsAggregationProperties effectiveProperties =
                aggregationProperties == null ? AsianOddsAggregationProperties.defaults() : aggregationProperties;
        this.matchMapper = matchMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
        this.predictionMapper = predictionMapper;
        this.predictionImportService = predictionImportService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.consensusAggregator = new AsianMarketConsensusAggregator(effectiveProperties);
        this.freezeSnapshotSelector = new AsianFreezeSnapshotSelector(effectiveProperties);
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public BaselinePredictionGenerationVo generateAndImport(LocalDate lotteryDate, String operatorUsername) {
        if (lotteryDate == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "lotteryDate must not be null");
        }

        // 1) 只读取持久化比赛与已确认映射的亚盘报价，不调用外部 Provider。
        List<MatchEntity> matches = matchMapper.selectPublicDailyMatches(lotteryDate);
        List<Long> matchIds = matchIds(matches);
        Map<Long, List<AsianOddsSnapshot>> handicapRowsByMatchId = handicapRowsByMatchId(matchIds);
        Map<Long, List<AsianMarketConsensusAggregator.Quote>> totalsQuotesByMatchId = totalsQuotesByMatchId(matchIds);

        // 2) 按比赛状态、开赛时间、共识主盘与可冻结行筛选输入。
        Instant now = clock.instant();
        List<FeatureSet> featureSets = new ArrayList<>();
        Map<BaselinePredictionSkipReasonEnum, Integer> skipped =
                new EnumMap<>(BaselinePredictionSkipReasonEnum.class);
        for (MatchEntity match : matches) {
            FeatureSet featureSet = selectFeatureSet(
                    match, handicapRowsByMatchId, totalsQuotesByMatchId, now, skipped);
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

    private List<Long> matchIds(List<MatchEntity> matches) {
        return matches.stream()
                .filter(Objects::nonNull)
                .map(MatchEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Map<Long, List<AsianOddsSnapshot>> handicapRowsByMatchId(List<Long> matchIds) {
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AsianOddsSnapshot>> result = new LinkedHashMap<>();
        for (AsianOddsSnapshot row : asianOddsSnapshotMapper.selectLatestConfirmedHandicapLinesByMatchIds(matchIds)) {
            if (row != null && row.getMatchId() != null) {
                result.computeIfAbsent(row.getMatchId(), key -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }

    private Map<Long, List<AsianMarketConsensusAggregator.Quote>> totalsQuotesByMatchId(List<Long> matchIds) {
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AsianMarketConsensusAggregator.Quote>> result = new LinkedHashMap<>();
        for (AsianOddsSnapshot row : asianOddsSnapshotMapper.selectLatestConfirmedTotalsLinesByMatchIds(matchIds)) {
            if (row == null || row.getMatchId() == null) {
                continue;
            }
            result.computeIfAbsent(row.getMatchId(), key -> new ArrayList<>()).add(new AsianMarketConsensusAggregator.Quote(
                    row.getProviderCode(),
                    row.getBookmakerCode(),
                    row.getTotalLine(),
                    row.getOverOdds(),
                    row.getUnderOdds(),
                    row.getCapturedAt(),
                    row.getId()
            ));
        }
        return result;
    }

    private FeatureSet selectFeatureSet(
            MatchEntity match,
            Map<Long, List<AsianOddsSnapshot>> handicapRowsByMatchId,
            Map<Long, List<AsianMarketConsensusAggregator.Quote>> totalsQuotesByMatchId,
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
        List<AsianOddsSnapshot> rows = handicapRowsByMatchId.getOrDefault(match.getId(), List.of());
        if (rows.isEmpty()) {
            skip(skipped, BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET);
            return null;
        }
        boolean anyUsablePrice = rows.stream().anyMatch(row ->
                AsianMarketMath.isUsablePrice(row.getHomeOdds())
                        && AsianMarketMath.isUsablePrice(row.getAwayOdds())
                        && row.getCapturedAt() != null);
        if (!anyUsablePrice) {
            skip(skipped, BaselinePredictionSkipReasonEnum.INVALID_ASIAN_MARKET);
            return null;
        }

        List<AsianMarketConsensusAggregator.Quote> handicapQuotes = rows.stream()
                .map(this::handicapQuote)
                .toList();
        AsianMarketConsensusAggregator.MarketAggregate handicapAggregate =
                consensusAggregator.aggregate(handicapQuotes);
        if (handicapAggregate.isEmpty()) {
            skip(skipped, BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS);
            recordConsensus("handicap", null);
            return null;
        }
        AsianMarketConsensusAggregator.LineConsensus handicapConsensus =
                handicapAggregate.best().orElseThrow();
        recordConsensus("handicap", handicapConsensus);

        AsianMarketConsensusAggregator.MarketAggregate totalsAggregate =
                consensusAggregator.aggregate(totalsQuotesByMatchId.getOrDefault(match.getId(), List.of()));
        AsianMarketConsensusAggregator.LineConsensus totalsBest = totalsAggregate.best().orElse(null);
        recordConsensus("totals", totalsBest);

        Optional<AsianOddsSnapshot> frozenRow =
                freezeSnapshotSelector.select(rows, handicapConsensus, Optional.ofNullable(totalsBest));
        if (frozenRow.isEmpty()) {
            skip(skipped, BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE);
            return null;
        }
        AsianOddsSnapshot frozen = frozenRow.get();
        AsianMarketConsensusAggregator.LineConsensus totalsAtFrozenLine =
                totalsAggregate.atLine(frozen.getTotalLine()).orElse(null);
        recordConsensus("totals-at-frozen-line", totalsAtFrozenLine);
        return new FeatureSet(
                match, frozen, handicapConsensus, totalsAtFrozenLine, totalsBest,
                frozen.getCapturedAt().truncatedTo(ChronoUnit.MICROS)
        );
    }

    private AsianMarketConsensusAggregator.Quote handicapQuote(AsianOddsSnapshot row) {
        return new AsianMarketConsensusAggregator.Quote(
                row.getProviderCode(),
                row.getBookmakerCode(),
                row.getHandicapLine(),
                row.getHomeOdds(),
                row.getAwayOdds(),
                row.getCapturedAt(),
                row.getId()
        );
    }

    private PredictionImportDto toPrediction(FeatureSet featureSet, Instant generatedAt) {
        AsianOddsSnapshot frozen = featureSet.frozen();
        ConfidenceLevelEnum handicapConfidence = confidence(frozen.getHomeOdds(), frozen.getAwayOdds());
        AsianHandicapPickEnum handicapPick = handicapConfidence == ConfidenceLevelEnum.LOW
                ? null
                : (favorsFirstSide(featureSet.handicapConsensus(), frozen.getHomeOdds(), frozen.getAwayOdds())
                        ? AsianHandicapPickEnum.HOME_COVER
                        : AsianHandicapPickEnum.AWAY_COVER);

        ConfidenceLevelEnum totalsConfidence = null;
        TotalGoalsPickEnum totalsPick = null;
        if (featureSet.totalsAtFrozenLine() != null) {
            totalsConfidence = confidence(frozen.getOverOdds(), frozen.getUnderOdds());
            if (totalsConfidence != ConfidenceLevelEnum.LOW) {
                totalsPick = favorsFirstSide(
                        featureSet.totalsAtFrozenLine(), frozen.getOverOdds(), frozen.getUnderOdds())
                        ? TotalGoalsPickEnum.OVER
                        : TotalGoalsPickEnum.UNDER;
            }
        }
        if (handicapPick == null && totalsPick == null) {
            return null;
        }
        return new PredictionImportDto(
                featureSet.matchId(), MODEL_VERSION, FEATURE_VERSION,
                null, null, null, null, null, null,
                summary(featureSet, handicapPick, handicapConfidence, totalsPick, totalsConfidence), generatedAt,
                frozen.getId(), handicapPick, totalsPick,
                handicapPick == null ? null : handicapConfidence,
                totalsPick == null ? null : totalsConfidence,
                PredictionTypeEnum.ASIAN
        );
    }

    /**
     * 方向取自跨庄共识；共识恰好 50/50 时回退到冻结行自身的水位高低。
     *
     * <p>不能用冻结行水位直接定方向：那是单家观点，会把「押低水方」的老逻辑带回来。</p>
     */
    private boolean favorsFirstSide(
            AsianMarketConsensusAggregator.LineConsensus consensus,
            BigDecimal firstPrice,
            BigDecimal secondPrice
    ) {
        int comparison = consensus.firstProbability().compareTo(HALF);
        if (comparison > 0) {
            return true;
        }
        if (comparison < 0) {
            return false;
        }
        return firstPrice.compareTo(secondPrice) <= 0;
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
            FeatureSet featureSet,
            AsianHandicapPickEnum handicapPick,
            ConfidenceLevelEnum handicapConfidence,
            TotalGoalsPickEnum totalsPick,
            ConfidenceLevelEnum totalsConfidence
    ) {
        AsianOddsSnapshot frozen = featureSet.frozen();
        AsianMarketConsensusAggregator.LineConsensus handicap = featureSet.handicapConsensus();
        StringBuilder text = new StringBuilder(420);
        text.append("亚盘共识基线 v4：快照 #").append(frozen.getId())
                .append("（").append(frozen.getBookmakerCode()).append(" / ").append(frozen.getProviderCode())
                .append("），让球主盘 ").append(handicap.line().toPlainString())
                .append(" 由").append(handicap.sampleSource()).append("去水共识选出")
                .append("，共识概率 主 ").append(probability(handicap.firstProbability()))
                .append(" / 客 ").append(probability(handicap.secondProbability()))
                .append("，σ ").append(probability(handicap.dispersion()))
                .append("，公平价 ").append(handicap.fairFirstPrice()).append("/").append(handicap.fairSecondPrice())
                .append("，冻结报价 ").append(frozen.getHomeOdds()).append("/").append(frozen.getAwayOdds())
                .append("；让球 ").append(frozen.getHandicapLine().toPlainString()).append(" ")
                .append(handicapPick == null
                        ? "方向因低置信度不发布"
                        : "为 " + handicapPick.getDesc() + "（" + handicapConfidence.getDesc() + "）")
                .append("；");

        if (featureSet.totalsAtFrozenLine() == null) {
            text.append("大小球该判定线聚合家数不足，不生成方向；");
        } else {
            AsianMarketConsensusAggregator.LineConsensus totals = featureSet.totalsAtFrozenLine();
            text.append("大小球 判定线 ").append(frozen.getTotalLine().toPlainString());
            if (featureSet.totalsBest() != null
                    && featureSet.totalsBest().line().compareTo(frozen.getTotalLine()) != 0) {
                text.append("（大小球主盘 ").append(featureSet.totalsBest().line().toPlainString())
                        .append("，该行未报主盘线）");
            }
            text.append(" ").append(totalsPick == null
                            ? "方向因低置信度不发布"
                            : "为 " + totalsPick.getDesc() + "（" + totalsConfidence.getDesc() + "）")
                    .append("，共识概率 大 ").append(probability(totals.firstProbability()))
                    .append(" / 小 ").append(probability(totals.secondProbability()))
                    .append("，σ ").append(probability(totals.dispersion()))
                    .append("，冻结报价 ").append(frozen.getOverOdds()).append("/").append(frozen.getUnderOdds())
                    .append("；");
        }
        text.append("方向取自跨庄共识、置信度按冻结报价计算，只作为方向筛选，不代表独立校准概率或正期望。");
        return text.toString();
    }

    private String probability(BigDecimal value) {
        return value.setScale(SUMMARY_PROBABILITY_SCALE, RoundingMode.HALF_UP).toPlainString();
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

    /**
     * 记录共识样本来源。
     *
     * <p>白名单编码未归一化时 {@code source=fallback} 会立刻占到 100%，是数据源接入阶段的早期告警。</p>
     */
    private void recordConsensus(String market, AsianMarketConsensusAggregator.LineConsensus consensus) {
        String source = consensus == null
                ? "none"
                : (consensus.whitelistApplied() ? "whitelist" : "fallback");
        String outcome = consensus == null ? "insufficient" : "ok";
        Counter.builder(CONSENSUS_METRIC)
                .tags("market", market, "source", source, "outcome", outcome)
                .register(meterRegistry)
                .increment();
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

    private record FeatureSet(
            MatchEntity match,
            AsianOddsSnapshot frozen,
            AsianMarketConsensusAggregator.LineConsensus handicapConsensus,
            AsianMarketConsensusAggregator.LineConsensus totalsAtFrozenLine,
            AsianMarketConsensusAggregator.LineConsensus totalsBest,
            Instant featureAsOf
    ) {
        Long matchId() {
            return match.getId();
        }
    }
}
