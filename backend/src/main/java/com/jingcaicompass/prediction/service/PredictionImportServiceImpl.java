package com.jingcaicompass.prediction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.prediction.dto.PredictionImportBatchDto;
import com.jingcaicompass.prediction.dto.PredictionImportDto;
import com.jingcaicompass.prediction.dto.PredictionImportResultDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 严格校验离线模型结果，并以可重放批次写入 DRAFT 预测。 */
@Service
@ConditionalOnBean(DataSource.class)
public class PredictionImportServiceImpl implements PredictionImportService {

    private static final int PROBABILITY_SCALE = 6;
    private static final int EXPECTED_GOALS_SCALE = 2;
    private static final BigDecimal MIN_PROBABILITY_SUM = new BigDecimal("0.999999");
    private static final BigDecimal MAX_PROBABILITY_SUM = new BigDecimal("1.000001");
    private static final BigDecimal MAX_EXPECTED_GOALS = new BigDecimal("999.99");
    private static final Pattern SHA_256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<MatchStatusEnum> IMPORTABLE_MATCH_STATUSES =
            Set.of(MatchStatusEnum.SCHEDULED, MatchStatusEnum.LOCKED);
    private static final List<String> FORBIDDEN_SUMMARY_PHRASES = List.of(
            "稳赚",
            "包赢",
            "必赚",
            "保证盈利",
            "保证收益",
            "保本",
            "无风险",
            "guaranteed profit",
            "risk-free"
    );

    private final PredictionImportFileParser fileParser;
    private final MatchMapper matchMapper;
    private final PredictionMapper predictionMapper;
    private final PredictionImportWriter importWriter;
    private final Clock clock;

    public PredictionImportServiceImpl(
            PredictionImportFileParser fileParser,
            MatchMapper matchMapper,
            PredictionMapper predictionMapper,
            PredictionImportWriter importWriter,
            Clock clock
    ) {
        this.fileParser = fileParser;
        this.matchMapper = matchMapper;
        this.predictionMapper = predictionMapper;
        this.importWriter = importWriter;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PredictionImportResultDto importFile(byte[] fileContent) {
        // 1) 严格解析 JSON，哈希基于原始文件字节生成
        PredictionImportBatchDto parsed = fileParser.parse(fileContent);

        // 2) 校验并规范化全部模型字段，尚不执行任何写操作
        NormalizedBatch batch = normalizeBatch(parsed);

        // 3) 完全相同的历史批次直接复用；批次 ID 冲突则拒绝
        PredictionImportResultDto reused = reuseExistingBatch(batch);
        if (reused != null) {
            return reused;
        }

        // 4) 批量加载比赛并确认当前仍允许导入
        validateMatches(batch.predictions());

        // 5) 为每个比赛/模型分配下一历史版本，构造完整 DRAFT 集合
        List<Prediction> predictions = buildDraftPredictions(batch);

        // 6) 在同一事务内整批写入，约束冲突转换为稳定业务错误
        try {
            List<Prediction> inserted = importWriter.writeAll(predictions);
            return result(batch, inserted, inserted.size(), 0);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "prediction import conflict for batch: " + batch.generationBatchId(),
                    exception
            );
        }
    }

    private NormalizedBatch normalizeBatch(PredictionImportBatchDto batch) {
        if (batch == null) {
            throw invalid("prediction import batch must not be null");
        }
        String batchId = requireText(batch.generationBatchId(), "generationBatchId", 128);
        if (!SHA_256_PATTERN.matcher(Objects.toString(batch.generationBatchHash(), "")).matches()) {
            throw invalid("generationBatchHash must be a lowercase SHA-256 hex value");
        }
        if (batch.predictions() == null || batch.predictions().isEmpty()) {
            throw invalid("predictions must not be empty");
        }

        List<PredictionImportDto> normalized = new ArrayList<>(batch.predictions().size());
        Set<PredictionKey> uniqueKeys = new HashSet<>();
        for (int index = 0; index < batch.predictions().size(); index++) {
            PredictionImportDto item = normalizeItem(batch.predictions().get(index), index);
            PredictionKey key = new PredictionKey(item.matchId(), item.modelVersion());
            if (!uniqueKeys.add(key)) {
                throw invalid("duplicate match/model in batch: " + item.matchId() + "/" + item.modelVersion());
            }
            normalized.add(item);
        }
        return new NormalizedBatch(batchId, batch.generationBatchHash(), List.copyOf(normalized));
    }

    private PredictionImportDto normalizeItem(PredictionImportDto item, int index) {
        String fieldPrefix = "predictions[" + index + "]";
        if (item == null) {
            throw invalid(fieldPrefix + " must not be null");
        }
        if (item.matchId() == null || item.matchId() <= 0) {
            throw invalid(fieldPrefix + ".matchId must be positive");
        }
        String modelVersion = requireText(item.modelVersion(), fieldPrefix + ".modelVersion", 64);
        String featureVersion = requireText(item.featureVersion(), fieldPrefix + ".featureVersion", 64);
        BigDecimal home = normalizeProbability(item.homeWinProb(), fieldPrefix + ".homeWinProb");
        BigDecimal draw = normalizeProbability(item.drawProb(), fieldPrefix + ".drawProb");
        BigDecimal away = normalizeProbability(item.awayWinProb(), fieldPrefix + ".awayWinProb");
        BigDecimal probabilitySum = home.add(draw).add(away);
        if (probabilitySum.compareTo(MIN_PROBABILITY_SUM) < 0
                || probabilitySum.compareTo(MAX_PROBABILITY_SUM) > 0) {
            throw invalid(fieldPrefix + " probability sum must be within 1 +/- 0.000001");
        }
        if (item.handicapPick() == null) {
            throw invalid(fieldPrefix + ".handicapPick must not be null");
        }
        BigDecimal expectedGoals = normalizeExpectedGoals(
                item.expectedTotalGoals(),
                fieldPrefix + ".expectedTotalGoals"
        );
        if (item.confidenceLevel() == null) {
            throw invalid(fieldPrefix + ".confidenceLevel must not be null");
        }
        String summary = requireText(item.analysisSummary(), fieldPrefix + ".analysisSummary", 1000);
        validateSummary(summary, fieldPrefix);
        if (item.generatedAt() == null) {
            throw invalid(fieldPrefix + ".generatedAt must not be null");
        }

        return new PredictionImportDto(
                item.matchId(),
                modelVersion,
                featureVersion,
                home,
                draw,
                away,
                item.handicapPick(),
                expectedGoals,
                item.confidenceLevel(),
                summary,
                item.generatedAt().truncatedTo(ChronoUnit.MICROS)
        );
    }

    private BigDecimal normalizeProbability(BigDecimal value, String field) {
        if (value == null) {
            throw invalid(field + " must not be null");
        }
        if (value.scale() > PROBABILITY_SCALE) {
            throw invalid(field + " must have at most 6 decimal places");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(field + " must be between 0 and 1");
        }
        return value.setScale(PROBABILITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal normalizeExpectedGoals(BigDecimal value, String field) {
        if (value == null) {
            throw invalid(field + " must not be null");
        }
        if (value.scale() > EXPECTED_GOALS_SCALE) {
            throw invalid(field + " must have at most 2 decimal places");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(MAX_EXPECTED_GOALS) > 0) {
            throw invalid(field + " must be between 0 and 999.99");
        }
        return value.setScale(EXPECTED_GOALS_SCALE, RoundingMode.UNNECESSARY);
    }

    private void validateSummary(String summary, String fieldPrefix) {
        String normalized = summary.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN_SUMMARY_PHRASES) {
            if (normalized.contains(phrase)) {
                throw invalid(fieldPrefix + ".analysisSummary contains prohibited promise language");
            }
        }
    }

    private PredictionImportResultDto reuseExistingBatch(NormalizedBatch batch) {
        List<Prediction> existing = predictionMapper.selectList(new LambdaQueryWrapper<Prediction>()
                .eq(Prediction::getGenerationBatchId, batch.generationBatchId()));
        if (existing.isEmpty()) {
            return null;
        }
        if (existing.size() != batch.predictions().size()
                || existing.stream().anyMatch(item ->
                        !batch.generationBatchHash().equals(item.getGenerationBatchHash()))) {
            throw batchConflict(batch.generationBatchId());
        }

        Map<PredictionKey, Prediction> byKey = new HashMap<>();
        for (Prediction prediction : existing) {
            byKey.put(new PredictionKey(prediction.getMatchId(), prediction.getModelVersion()), prediction);
        }

        List<Prediction> ordered = new ArrayList<>(batch.predictions().size());
        for (PredictionImportDto item : batch.predictions()) {
            Prediction prediction = byKey.get(new PredictionKey(item.matchId(), item.modelVersion()));
            if (prediction == null || !sameCorePrediction(prediction, item)) {
                throw batchConflict(batch.generationBatchId());
            }
            ordered.add(prediction);
        }
        return result(batch, ordered, 0, ordered.size());
    }

    private boolean sameCorePrediction(Prediction prediction, PredictionImportDto item) {
        return Objects.equals(prediction.getFeatureVersion(), item.featureVersion())
                && decimalEquals(prediction.getHomeWinProb(), item.homeWinProb())
                && decimalEquals(prediction.getDrawProb(), item.drawProb())
                && decimalEquals(prediction.getAwayWinProb(), item.awayWinProb())
                && prediction.getHandicapPick() == item.handicapPick()
                && decimalEquals(prediction.getExpectedTotalGoals(), item.expectedTotalGoals())
                && prediction.getConfidenceLevel() == item.confidenceLevel()
                && Objects.equals(prediction.getAnalysisSummary(), item.analysisSummary())
                && Objects.equals(prediction.getGeneratedAt(), item.generatedAt());
    }

    private void validateMatches(List<PredictionImportDto> predictions) {
        List<Long> matchIds = predictions.stream()
                .map(PredictionImportDto::matchId)
                .distinct()
                .toList();
        Map<Long, MatchEntity> matches = matchMapper.selectBatchIds(matchIds).stream()
                .collect(java.util.stream.Collectors.toMap(MatchEntity::getId, match -> match));
        Instant now = clock.instant();
        for (Long matchId : matchIds) {
            MatchEntity match = matches.get(matchId);
            if (match == null) {
                throw invalid("match not found: " + matchId);
            }
            if (!IMPORTABLE_MATCH_STATUSES.contains(match.getMatchStatus())) {
                throw invalid("match status does not allow prediction import: "
                        + matchId + "/" + match.getMatchStatus());
            }
            if (match.getKickoffTime() == null || !match.getKickoffTime().isAfter(now)) {
                throw invalid("match already started: " + matchId);
            }
        }
    }

    private List<Prediction> buildDraftPredictions(NormalizedBatch batch) {
        List<Prediction> predictions = new ArrayList<>(batch.predictions().size());
        for (PredictionImportDto item : batch.predictions()) {
            Prediction latest = predictionMapper.selectOne(new LambdaQueryWrapper<Prediction>()
                    .eq(Prediction::getMatchId, item.matchId())
                    .eq(Prediction::getModelVersion, item.modelVersion())
                    .orderByDesc(Prediction::getPredictionVersion)
                    .last("LIMIT 1"));
            int nextVersion = nextVersion(latest, item);

            Prediction prediction = new Prediction();
            prediction.setMatchId(item.matchId());
            prediction.setModelVersion(item.modelVersion());
            prediction.setFeatureVersion(item.featureVersion());
            prediction.setGenerationBatchId(batch.generationBatchId());
            prediction.setGenerationBatchHash(batch.generationBatchHash());
            prediction.setPredictionVersion(nextVersion);
            prediction.setHomeWinProb(item.homeWinProb());
            prediction.setDrawProb(item.drawProb());
            prediction.setAwayWinProb(item.awayWinProb());
            prediction.setHandicapPick(item.handicapPick());
            prediction.setExpectedTotalGoals(item.expectedTotalGoals());
            prediction.setConfidenceLevel(item.confidenceLevel());
            prediction.setAnalysisSummary(item.analysisSummary());
            prediction.setGeneratedAt(item.generatedAt());
            prediction.setPredictionStatus(PredictionStatusEnum.DRAFT);
            predictions.add(prediction);
        }
        return predictions;
    }

    private int nextVersion(Prediction latest, PredictionImportDto item) {
        if (latest == null) {
            return 1;
        }
        if (latest.getPredictionVersion() == null
                || latest.getPredictionVersion() == Integer.MAX_VALUE) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "prediction version exhausted: " + item.matchId() + "/" + item.modelVersion()
            );
        }
        return latest.getPredictionVersion() + 1;
    }

    private PredictionImportResultDto result(
            NormalizedBatch batch,
            List<Prediction> predictions,
            int insertedCount,
            int reusedCount
    ) {
        return new PredictionImportResultDto(
                batch.generationBatchId(),
                batch.generationBatchHash(),
                predictions.size(),
                insertedCount,
                reusedCount,
                predictions.stream().map(Prediction::getId).toList()
        );
    }

    private String requireText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw invalid(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw invalid(field + " length must not exceed " + maxLength);
        }
        return normalized;
    }

    private boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private BusinessException batchConflict(String batchId) {
        return new BusinessException(
                ErrorCode.BUSINESS_ERROR,
                "generation batch conflicts with existing predictions: " + batchId
        );
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_PARAMETER, message);
    }

    private record NormalizedBatch(
            String generationBatchId,
            String generationBatchHash,
            List<PredictionImportDto> predictions
    ) {
    }

    private record PredictionKey(Long matchId, String modelVersion) {
    }
}
