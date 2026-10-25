package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.ActiveAsianOddsProvider;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.odds.service.AsianPublishGate;
import com.jingcaicompass.odds.service.ImpliedPoissonModel;
import com.jingcaicompass.prediction.dto.AsianModelOutputDto;
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
import java.util.Locale;
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
 * 以两个市场的主盘概率反解 λ，再把 λ 投影回冻结行自身盘口线来生成分项方向草稿。
 *
 * <p>取数从「每场最新一条完整快照」改为「每场每家每档线的最新报价」：主盘必须由聚合选出，
 * 取数阶段一旦收敛到单条就无法判断哪条线最平衡。结算仍只认冻结行，所以决策线与计账线必然一致。</p>
 *
 * <p>方向改由模型概率给出而不是直接读共识概率：阶段 D 的 edge 由模型概率算，若方向取其一共识而
 * edge 取模型，会出现「方向说主队、edge 说客队」的自相矛盾记录。两者在主盘上几乎必然一致
 * （残差在 1e-16 量级），因此这次切换不改变既有行为，只统一了来源。</p>
 *
 * <p>阶段 D 起有两道判定：跨庄分歧（{@link AsianPublishGate#exceedsDispersion}）**真拦**，
 * 按市场独立否决；edge（{@link AsianPublishGate#evaluate}）是**影子门槛**，只写进摘要与指标，
 * 不改变生成结果——它在让球主盘上结构性恒为 0，原因见 {@link AsianPublishGate} 的类注释。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BaselinePredictionGenerationServiceImpl implements BaselinePredictionGenerationService {

    /** 公开给回测记录版本锚点：参数组不同但模型版本相同，才能判断差异来自阈值还是来自模型。 */
    public static final String MODEL_VERSION = "t306-asian-baseline-v6";
    public static final String FEATURE_VERSION = "t306-asian-mainline-v6";

    private static final Logger LOG = LoggerFactory.getLogger(BaselinePredictionGenerationServiceImpl.class);
    private static final int SUMMARY_PROBABILITY_SCALE = 4;
    /** 落库标度：与 V24 列及导入校验的 6 位小数口径一致，多一位会被导入直接拒绝。 */
    private static final int MODEL_OUTPUT_SCALE = 6;

    private final MatchMapper matchMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final PredictionMapper predictionMapper;
    private final PredictionImportService predictionImportService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AsianDecisionEngine decisionEngine;
    private final MeterRegistry meterRegistry;
    private final ActiveAsianOddsProvider activeAsianOddsProvider;

    public BaselinePredictionGenerationServiceImpl(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper unusedSportteryPoolSnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            PredictionMapper predictionMapper,
            PredictionImportService predictionImportService,
            ObjectMapper objectMapper,
            Clock clock,
            AsianOddsAggregationProperties aggregationProperties,
            AsianModelProperties modelProperties,
            MeterRegistry meterRegistry,
            ActiveAsianOddsProvider activeAsianOddsProvider
    ) {
        this.matchMapper = matchMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
        this.predictionMapper = predictionMapper;
        this.predictionImportService = predictionImportService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        // 判定规则全部在引擎里，这里注入真实 registry 以保留既有指标；
        // 阈值来自 AsianModelProperties，默认值与原先硬编码的常量逐字相等。
        this.decisionEngine = new AsianDecisionEngine(aggregationProperties, modelProperties, meterRegistry);
        this.meterRegistry = meterRegistry;
        this.activeAsianOddsProvider = activeAsianOddsProvider;
    }

    @Override
    @Transactional
    public BaselinePredictionGenerationVo generateAndImport(LocalDate lotteryDate, String operatorUsername) {
        if (lotteryDate == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "lotteryDate must not be null");
        }

        // 1) 只读取持久化比赛与当前来源的亚盘报价，不调用外部 Provider。
        List<MatchEntity> matches = matchMapper.selectPublicDailyMatches(lotteryDate);
        List<Long> matchIds = matchIds(matches);
        Map<Long, List<AsianOddsSnapshot>> handicapRowsByMatchId = handicapRowsByMatchId(matchIds);
        Map<Long, List<AsianOddsSnapshot>> freezeRowsByMatchId = freezeRowsByMatchId(matchIds);
        Map<Long, List<AsianMarketConsensusAggregator.Quote>> totalsQuotesByMatchId = totalsQuotesByMatchId(matchIds);

        // 2) 逐场走决策引擎：状态/时间闸门、两个市场的主盘共识、λ 反解与邻线校验、冻结行。
        //    引擎与回测共用同一份实现，这里只负责取数与计数，不含任何判定规则。
        Instant now = clock.instant();
        List<AsianDecision> decisions = new ArrayList<>();
        Map<BaselinePredictionSkipReasonEnum, Integer> skipped =
                new EnumMap<>(BaselinePredictionSkipReasonEnum.class);
        for (MatchEntity match : matches) {
            if (match == null || match.getId() == null) {
                skip(skipped, BaselinePredictionSkipReasonEnum.NOT_SCHEDULED);
                continue;
            }
            AsianDecision decision = decisionEngine.decide(new AsianDecisionInput(
                    match.getId(),
                    match.getMatchStatus(),
                    match.getKickoffTime(),
                    now,
                    handicapRowsByMatchId.getOrDefault(match.getId(), List.of()),
                    totalsQuotesByMatchId.getOrDefault(match.getId(), List.of()),
                    freezeRowsByMatchId.getOrDefault(match.getId(), List.of())));
            if (decision.skipReason() != null) {
                skip(skipped, decision.skipReason());
            } else {
                decisions.add(decision);
            }
        }

        // 3) 方向已判定完毕，这里只按 matchId 升序组装落库 DTO。
        //    顺序不能改：批次指纹与导入顺序都依赖它，改成赛程顺序会让 generation_batch_hash 变化。
        decisions.sort(Comparator.comparing(AsianDecision::matchId));
        List<PredictionImportDto> predictions = new ArrayList<>();
        for (AsianDecision decision : decisions) {
            predictions.add(toPrediction(decision, decision.featureAsOf()));
        }
        if (predictions.isEmpty()) {
            recordMetrics(matches.size(), 0, skipped);
            logResult(lotteryDate, operatorUsername, matches.size(), 0, null, skipped, "EMPTY");
            return emptyResult(lotteryDate, matches.size(), skipped);
        }
        Instant generatedAt = decisions.stream()
                .map(AsianDecision::featureAsOf)
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
        String providerCode = activeAsianOddsProvider.providerCode();
        for (AsianOddsSnapshot row
                : asianOddsSnapshotMapper.selectLatestHandicapLinesByMatchIds(matchIds, providerCode)) {
            if (row != null && row.getMatchId() != null) {
                result.computeIfAbsent(row.getMatchId(), key -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }

    /**
     * 冻结行候选：完整交叉积（每家 × 让球线 × 大小球线）。
     *
     * <p>与 {@link #handicapRowsByMatchId} 分开取，不是重复劳动。聚合只要「每家每档让球线一票」，
     * 而挑冻结行要能在同一档让球线的多档大小球里选到主盘那一档。合成一条查询会让聚合读到
     * 四倍的重复报价（虽然聚合器自己按 {@code (bookmaker, line)} 去重、结果等价，但那是它的
     * 内部实现细节，不该被上游依赖）。</p>
     */
    private Map<Long, List<AsianOddsSnapshot>> freezeRowsByMatchId(List<Long> matchIds) {
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AsianOddsSnapshot>> result = new LinkedHashMap<>();
        String providerCode = activeAsianOddsProvider.providerCode();
        for (AsianOddsSnapshot row
                : asianOddsSnapshotMapper.selectLatestCrossProductLinesByMatchIds(matchIds, providerCode)) {
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
        String providerCode = activeAsianOddsProvider.providerCode();
        for (AsianOddsSnapshot row
                : asianOddsSnapshotMapper.selectLatestTotalsLinesByMatchIds(matchIds, providerCode)) {
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

    /**
     * 把引擎的判定结果组装成落库 DTO。
     *
     * <p>这里不再做任何判定：方向、置信度、分歧否决与影子门槛全在 {@link AsianDecisionEngine} 里完成，
     * 生产与回测因此不可能各走一套。置信度按「有方向才落」的规则写库——引擎在低置信时仍保留
     * {@code LOW} 供摘要使用，直接落库会撞导入校验的「无方向 ⇒ 无置信度」。</p>
     */
    private PredictionImportDto toPrediction(AsianDecision decision, Instant generatedAt) {
        AsianOddsSnapshot frozen = decision.frozen();
        AsianDecision.HandicapLeg handicap = Objects.requireNonNull(decision.handicap());
        AsianDecision.TotalsLeg totals = Objects.requireNonNull(decision.totals());
        return new PredictionImportDto(
                decision.matchId(), MODEL_VERSION, FEATURE_VERSION,
                null, null, null, null, null, null,
                summary(decision),
                generatedAt,
                frozen.getId(), handicap.pick(), totals.pick(),
                handicap.pick() == null ? null : handicap.confidence(),
                totals.pick() == null ? null : totals.confidence(),
                PredictionTypeEnum.ASIAN,
                asianModelOutput(decision));
    }

    /**
     * 组装落库用的亚盘模型输出，概率一律取<b>首边口径</b>（主队赢盘 / 大球）。
     *
     * <p>这里刻意<b>不用</b> {@link AsianPublishGate.Verdict} 里的概率：那个已按所选方向
     * 换算过（{@code AsianPublishGate} 的 {@code side} 分支会取 {@code 1 − p}），直接落库会让
     * 客队方向、小球方向的概率反号，模型看起来"神准"或"极差"。</p>
     *
     * <p>概率直接取引擎算好的那一份，不再投影第二次：同一个 {@code project(λ, 线, 市场)} 调用，
     * 重算不会有不同结果，但会让人误以为存在两个来源。</p>
     */
    private AsianModelOutputDto asianModelOutput(AsianDecision decision) {
        ImpliedPoissonModel.Solution solution = decision.solution();
        AsianDecision.HandicapLeg handicap = Objects.requireNonNull(decision.handicap());
        AsianDecision.TotalsLeg totals = Objects.requireNonNull(decision.totals());
        BigDecimal modelCover = scaled(handicap.coverProbability());
        BigDecimal marketCover = decision.handicapConsensus().firstProbability();
        BigDecimal modelOver = null;
        BigDecimal marketOver = null;
        BigDecimal edgeOver = null;
        BigDecimal totalsDispersion = null;
        if (decision.totalsAtFrozenLine() != null) {
            modelOver = scaled(totals.overProbability());
            marketOver = decision.totalsAtFrozenLine().firstProbability();
            edgeOver = modelOver.subtract(marketOver);
            totalsDispersion = decision.totalsAtFrozenLine().dispersion();
        }
        return new AsianModelOutputDto(
                scaled(solution.lambdaHome()),
                scaled(solution.lambdaAway()),
                modelCover,
                marketCover,
                modelCover.subtract(marketCover),
                modelOver,
                marketOver,
                edgeOver,
                decision.handicapConsensus().dispersion(),
                totalsDispersion
        );
    }

    private BigDecimal scaled(double value) {
        return BigDecimal.valueOf(value).setScale(MODEL_OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    private String summary(AsianDecision decision) {
        AsianOddsSnapshot frozen = decision.frozen();
        AsianDecision.HandicapLeg handicapLeg = Objects.requireNonNull(decision.handicap());
        AsianDecision.TotalsLeg totalsLeg = Objects.requireNonNull(decision.totals());
        AsianMarketConsensusAggregator.LineConsensus handicap = decision.handicapConsensus();
        ImpliedPoissonModel.Solution solution = decision.solution();
        ImpliedPoissonModel.NeighborCheck neighborCheck = decision.neighborCheck();
        // 分歧判定值直接取引擎的结论，不再二次调用 exceedsDispersion：原先重算是为了不往签名里
        // 多塞两个布尔参数，现在判定已经在引擎里做过了。
        boolean handicapDispersionExceeded = handicapLeg.dispersionExceeded();
        boolean totalsDispersionExceeded = totalsLeg.dispersionExceeded();
        StringBuilder text = new StringBuilder(560);
        text.append("亚盘 λ 反解 v6：快照 #").append(frozen.getId())
                .append("（").append(frozen.getBookmakerCode()).append(" / ").append(frozen.getProviderCode())
                .append("），让球主盘 ").append(handicap.line().toPlainString())
                .append(" 由").append(handicap.sampleSource()).append("去水共识选出")
                .append("，共识概率 主 ").append(probability(handicap.firstProbability()))
                .append(" / 客 ").append(probability(handicap.secondProbability()))
                .append("，σ ").append(probability(handicap.dispersion()))
                .append("，公平价 ").append(handicap.fairFirstPrice()).append("/").append(handicap.fairSecondPrice())
                .append("，冻结报价 ").append(frozen.getHomeOdds()).append("/").append(frozen.getAwayOdds())
                .append("；反解 λ 主 ").append(decimal(solution.lambdaHome()))
                .append(" / 客 ").append(decimal(solution.lambdaAway()))
                .append("（残差 ").append(scientific(solution.coverResidual()))
                .append(" / ").append(scientific(solution.overResidual()))
                .append("，").append(neighborCheckText(neighborCheck)).append("）；")
                .append("让球 ").append(frozen.getHandicapLine().toPlainString()).append(" ")
                .append(directionText(
                        handicapLeg.pick() == null ? null : handicapLeg.pick().getDesc(),
                        handicapLeg.confidence(),
                        handicapDispersionExceeded))
                .append("，模型概率 ").append(decimal(handicapLeg.coverProbability()))
                .append(gateText(handicapLeg.gate()))
                .append("；");

        // 判定线恒等于大小球主盘线（决策引擎的门禁保证），故此处分母非空。
        // 曾经这里还分「该行未报主盘线」「该判定线聚合家数不足」两支护，那是冻结行被交叉积去重
        // 抬高的产物：线都不是主盘，写出来的方向只是线位偏移，不是观点。门禁上线后两支都不可达。
        AsianMarketConsensusAggregator.LineConsensus totals = decision.totalsAtFrozenLine();
        text.append("大小球 判定线 ").append(frozen.getTotalLine().toPlainString())
                .append(" ").append(directionText(
                        totalsLeg.pick() == null ? null : totalsLeg.pick().getDesc(),
                        totalsLeg.confidence(),
                        totalsDispersionExceeded))
                .append("，共识概率 大 ").append(probability(totals.firstProbability()))
                .append(" / 小 ").append(probability(totals.secondProbability()))
                .append("，σ ").append(probability(totals.dispersion()))
                .append("，冻结报价 ").append(frozen.getOverOdds()).append("/").append(frozen.getUnderOdds())
                .append("，模型概率 ").append(decimal(totalsLeg.overProbability()))
                .append(gateText(totalsLeg.gate()))
                .append("；");
        text.append("模型概率由两个市场主盘反解 λ 后投影得到，仍源自市场定价，不构成独立校准概率或正期望；")
                .append("edge 与盈亏余量仅记录，本版本不据其筛选。");
        return text.toString();
    }

    /**
     * 方向措辞。
     *
     * <p>σ 否决与低置信度是两种互斥原因：前者说明各家定价打架、数据不可信，后者说明该家报价的
     * 水位结构不支持发布方向。混成一句会让排障无法归因。</p>
     */
    private String directionText(
            String pickDescription,
            ConfidenceLevelEnum confidence,
            boolean dispersionExceeded
    ) {
        if (pickDescription != null) {
            return "为 " + pickDescription + "（" + confidence.getDesc() + "）";
        }
        return dispersionExceeded ? "方向因跨庄分歧过大不发布" : "方向因低置信度不发布";
    }

    /**
     * 影子门槛的措辞；没有方向时返回空串。
     *
     * <p>刻意不写「优势 / 价值」，只写「仅记录」：非零 edge 只来自独立泊松在邻线上的形状偏差，
     * 不代表预测能力。同时避开导入侧的禁词表（保本 / 无风险 / 稳赚 等，命中即整批回滚）。</p>
     */
    private String gateText(AsianPublishGate.Verdict verdict) {
        if (verdict == null) {
            return "";
        }
        return "，edge " + signed(verdict.edge())
                + "、盈亏余量 " + signed(verdict.margin())
                + (verdict.wouldPublish()
                        ? "（影子门槛通过，仅记录）"
                        : "（影子门槛未通过）");
    }

    /** 带符号的 4 位小数；恰好为 0 时不带符号，避免出现 {@code +0.0000} 与 {@code -0.0000}。 */
    private String signed(BigDecimal value) {
        BigDecimal scaled = value.setScale(SUMMARY_PROBABILITY_SCALE, RoundingMode.HALF_UP);
        if (scaled.signum() == 0) {
            return scaled.toPlainString();
        }
        return scaled.signum() > 0 ? "+" + scaled.toPlainString() : scaled.toPlainString();
    }

    /**
     * 邻线校验结果的措辞。
     *
     * <p>没有合格邻线时必须明写「无合格邻线」而不是「偏差 0」：后者会被读成完美自洽。</p>
     */
    private String neighborCheckText(ImpliedPoissonModel.NeighborCheck neighborCheck) {
        if (!neighborCheck.validated()) {
            return "无合格邻线，未做自洽校验";
        }
        return "邻线最大偏差 " + decimal(neighborCheck.maxDeviation() * 100) + "%（"
                + neighborCheck.lineCount() + " 档）";
    }

    private String decimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(SUMMARY_PROBABILITY_SCALE, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private String scientific(double value) {
        return String.format(Locale.ROOT, "%.1e", value);
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
     * 按场次计数。仍留在本类而非引擎：候选数与生成数是「一轮生成」的口径，与单场判定无关。
     */
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

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

}
