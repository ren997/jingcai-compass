package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.ActiveAsianOddsProvider;
import com.jingcaicompass.prediction.dto.AsianModelOutputDto;
import com.jingcaicompass.prediction.dto.PredictionImportBatchDto;
import com.jingcaicompass.prediction.dto.PredictionImportDto;
import com.jingcaicompass.prediction.dto.PredictionImportResultDto;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** T306/T613-B 共识主盘预测基线的确定性、冻结口径与数据门槛测试。 */
@ExtendWith(MockitoExtension.class)
class BaselinePredictionGenerationServiceTest {

    private static final LocalDate LOTTERY_DATE = LocalDate.of(2026, 8, 11);
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant EARLIER_CAPTURE = Instant.parse("2026-08-10T01:00:00Z");
    private static final Instant LATEST_CAPTURE = Instant.parse("2026-08-10T02:00:00Z");
    private static final Instant NEWEST_CAPTURE = Instant.parse("2026-08-10T03:00:00Z");

    @Mock
    private MatchMapper matchMapper;
    @Mock
    private SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper;
    @Mock
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    @Mock
    private PredictionMapper predictionMapper;
    @Mock
    private PredictionImportService predictionImportService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private PredictionImportFileParser parser;
    private BaselinePredictionGenerationService service;

    @BeforeEach
    void setUp() {
        parser = new PredictionImportFileParserImpl(new ObjectMapper().findAndRegisterModules());
        service = serviceWith(AsianModelProperties.defaults());
    }

    /** 复用同一批 mock，只换模型阈值；断言 meter 需要固定注册表，故不在 setUp 里 new。 */
    private BaselinePredictionGenerationService serviceWith(AsianModelProperties properties) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new BaselinePredictionGenerationServiceImpl(
                matchMapper,
                sportteryPoolSnapshotMapper,
                asianOddsSnapshotMapper,
                predictionMapper,
                predictionImportService,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                AsianOddsAggregationProperties.defaults(),
                properties,
                meterRegistry,
                new ActiveAsianOddsProvider("NOVA_API")
        );
    }

    @Test
    void generatesDeterministicDraftInputFromConsensusMainLine() {
        MatchEntity match = scheduledMatch(101L);
        List<AsianOddsSnapshot> rows = List.of(
                row(501L, 101L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(502L, 101L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);
        stubImport();

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(predictionImportService).importFile(contentCaptor.capture());
        PredictionImportBatchDto batch = parser.parse(contentCaptor.getValue());
        assertThat(batch.generationBatchId()).startsWith("t306-asian-2026-08-11-");
        assertThat(batch.predictions()).singleElement().satisfies(prediction -> {
            assertThat(prediction.matchId()).isEqualTo(101L);
            assertThat(prediction.modelVersion()).isEqualTo(BaselinePredictionGenerationServiceImpl.MODEL_VERSION);
            assertThat(prediction.featureVersion()).isEqualTo(BaselinePredictionGenerationServiceImpl.FEATURE_VERSION);
            assertThat(prediction.modelVersion()).isEqualTo("t306-asian-baseline-v6");
            assertThat(prediction.homeWinProb()).isNull();
            assertThat(prediction.drawProb()).isNull();
            assertThat(prediction.awayWinProb()).isNull();
            assertThat(prediction.handicapPick()).isNull();
            assertThat(prediction.expectedTotalGoals()).isNull();
            // 结论线在 -0.50，且取报价最新且抽水最低的 BOOK_A。
            assertThat(prediction.asianOddsSnapshotId()).isEqualTo(501L);
            assertThat(prediction.asianHandicapPick()).isNull();
            assertThat(prediction.totalGoalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
            assertThat(prediction.asianHandicapConfidenceLevel()).isNull();
            assertThat(prediction.totalGoalsConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
            assertThat(prediction.confidenceLevel()).isNull();
            assertThat(prediction.analysisSummary())
                    .contains("亚盘 λ 反解 v6", "让球主盘 -0.50", "回退全量 2 家", "冻结报价 1.90/1.90",
                            "反解 λ 主", "模型概率", "不构成独立校准概率");
            assertThat(prediction.analysisSummary().length()).isLessThan(1000);
            assertThat(prediction.generatedAt()).isEqualTo(LATEST_CAPTURE);
        });
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(result.reusedCount()).isZero();
        assertThat(result.generationBatchId()).isEqualTo(batch.generationBatchId());
        assertThat(result.generatedAt()).isEqualTo(LATEST_CAPTURE);
        assertThat(result.skippedByReason()).isEmpty();
    }

    @Test
    void freezesSnapshotOnConsensusLineInsteadOfLatestSnapshot() {
        MatchEntity match = scheduledMatch(102L);
        List<AsianOddsSnapshot> rows = List.of(
                row(601L, 102L, "BOOK_A", "-0.25", "1.90", "1.90", "2.50", "1.80", "2.00", EARLIER_CAPTURE),
                row(602L, 102L, "BOOK_B", "-0.25", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                // 最新一条在 -1.50，但只有一家报价该线，不能成为主盘。
                row(603L, 102L, "BOOK_C", "-1.50", "1.60", "2.20", "2.50", "1.90", "1.90", NEWEST_CAPTURE));
        stubMarkets(match, rows, rows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        assertThat(capturedPrediction().asianOddsSnapshotId()).isIn(601L, 602L);
    }

    @Test
    void derivesDirectionFromModelProjectionWhileConfidenceStillUsesFrozenRow() {
        MatchEntity match = scheduledMatch(103L);
        // 共识 0.515 偏向主队（三家去水 0.485 / 0.515 / 0.525，σ≈0.017 未超限），
        // 但唯一可冻结的 BOOK_A 自身水位 0.485 偏向客队 —— 方向必须跟模型走，置信度看这一行的报价。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(701L, 103L, "BOOK_A", "-0.25", "2.06", "1.94", "2.50", "1.94", "1.94", LATEST_CAPTURE),
                // 没有大小球的两个水位，因此不参与冻结，只把让球中位数拉到主队一侧。
                row(702L, 103L, "BOOK_B", "-0.25", "1.94", "2.06", null, null, null, EARLIER_CAPTURE),
                row(703L, 103L, "BOOK_C", "-0.25", "1.90", "2.10", null, null, null, EARLIER_CAPTURE));
        List<AsianOddsSnapshot> totalsRows = List.of(
                row(704L, 103L, "BOOK_A", "-0.25", "2.06", "1.94", "2.50", "1.94", "1.94", LATEST_CAPTURE),
                row(705L, 103L, "BOOK_B", "-0.25", "1.94", "2.06", "2.50", "1.94", "1.94", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, totalsRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
        assertThat(prediction.asianHandicapConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
        // 1.94/1.94 完全对称 → 大小球信号为 0 → 低置信度不出方向。
        assertThat(prediction.totalGoalsPick()).isNull();
        assertThat(prediction.analysisSummary())
                .contains("共识概率 主 0.5150")
                // λ 是在主盘上反解出来的，回投必然复现共识，因此模型概率与共识概率在小数点后四位一致。
                .contains("模型概率 0.5150");
    }

    @Test
    void storesFirstSideProbabilitiesEvenWhenDirectionIsAwayCover() {
        MatchEntity match = scheduledMatch(133L);
        // 三家的让球去水概率都偏向客队（≈0.485 / 0.485 / 0.485），共识首边概率低于 0.5 → 方向为客队。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(731L, 133L, "BOOK_A", "-0.25", "2.06", "1.94", "2.50", "1.94", "1.94", LATEST_CAPTURE),
                row(732L, 133L, "BOOK_B", "-0.25", "2.02", "1.90", null, null, null, EARLIER_CAPTURE),
                row(733L, 133L, "BOOK_C", "-0.25", "2.00", "1.88", null, null, null, EARLIER_CAPTURE));
        List<AsianOddsSnapshot> totalsRows = List.of(
                row(734L, 133L, "BOOK_A", "-0.25", "2.06", "1.94", "2.50", "1.94", "1.94", LATEST_CAPTURE),
                row(735L, 133L, "BOOK_B", "-0.25", "2.02", "1.90", "2.50", "1.94", "1.94", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, totalsRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.AWAY_COVER);
        AsianModelOutputDto asian = prediction.asianModel();
        assertThat(asian).isNotNull();
        // 落库恒为首边口径：方向是客队，但存的仍是"主队赢盘"的概率，必须小于 0.5。
        // 若这里误存换算后的方向概率，值会是 0.515 一带，模型看起来就"神准"了。
        assertThat(asian.modelCoverProb()).isLessThan(BigDecimal.valueOf(0.5));
        assertThat(asian.marketCoverProb()).isLessThan(BigDecimal.valueOf(0.5));
        assertThat(asian.modelCoverProb()).isEqualByComparingTo(asian.marketCoverProb());
        assertThat(asian.edgeCover()).isEqualByComparingTo(BigDecimal.ZERO);
        // 方向换算只发生在展示侧：1 − 0.4847 ≈ 0.5153 才是模型对客队赢盘的看法。
        assertThat(BigDecimal.ONE.subtract(asian.modelCoverProb()))
                .isGreaterThan(BigDecimal.valueOf(0.5));
    }

    @Test
    void projectsTotalsProbabilitiesEvenWhenTotalsDirectionIsLowConfidence() {
        MatchEntity match = scheduledMatch(134L);
        // 大小球两个水位完全对称（1.94/1.94）→ 信号为 0 → 低置信度不出方向。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(741L, 134L, "BOOK_A", "-0.25", "1.94", "2.06", "2.50", "1.94", "1.94", LATEST_CAPTURE),
                row(742L, 134L, "BOOK_B", "-0.25", "1.94", "2.06", "2.50", "1.94", "1.94", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, handicapRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.totalGoalsPick()).isNull();
        assertThat(prediction.totalGoalsConfidenceLevel()).isNull();
        AsianModelOutputDto asian = prediction.asianModel();
        assertThat(asian).isNotNull();
        // 不出方向不等于大球概率为 0。若把 0 落库，Brier 会把"没有方向"当作"强烈看空"。
        assertThat(asian.modelOverProb()).isNotNull().isGreaterThan(BigDecimal.ZERO);
        // 两家去水后都是 0.5 → 共识 0.5、离散度 0。
        assertThat(asian.marketOverProb()).isEqualByComparingTo(new BigDecimal("0.500000"));
        assertThat(asian.totalsDispersion()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(asian.lambdaHome()).isNotNull();
        assertThat(asian.lambdaAway()).isNotNull();
    }

    /**
     * 冻结行必须落在大小球主盘那一档，而不是交叉积里最大的那一档。
     *
     * <p>这是 2026-10-22 那批「13 场全部小球」的回归守卫。NovaAPI 的落库形态是
     * (让球线 × 大小球线) 交叉积，同一档让球线下四档大小球按 {@code id} 递增写入；
     * 聚合取数按 {@code handicap_line} 去重只留最新一条 = 留最大那一档，于是冻结行被抬高，
     * 而 λ 由主盘反解、方向却投影回被抬高的线 → 系统性偏小球。</p>
     *
     * <p>本例刻意复刻真实数值（平博周四001 的让球 +0.50 四档）：主盘 2.75 是四档里
     * 最平衡的一档，3.25 是离 50/50 最远的一档。修复前冻结 3.25 判小球，修复后冻结 2.75 判大球。</p>
     */
    @Test
    void freezesOnTotalsConsensusLineNotOnLargestTotalsLineOfCrossProduct() {
        MatchEntity match = scheduledMatch(135L);
        // 聚合取数：每家每档让球线只剩一行，且是交叉积里最后写入的那档（3.25）。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(751L, 135L, "BOOK_A", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", LATEST_CAPTURE),
                row(752L, 135L, "BOOK_B", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", EARLIER_CAPTURE));
        // 冻结行候选：完整交叉积，同一档让球线下四档大小球齐全。
        List<AsianOddsSnapshot> crossProductRows = List.of(
                row(753L, 135L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.66", "2.29", LATEST_CAPTURE),
                row(754L, 135L, "BOOK_A", "-0.50", "1.90", "1.90", "2.75", "1.83", "2.07", LATEST_CAPTURE),
                row(755L, 135L, "BOOK_A", "-0.50", "1.90", "1.90", "3.00", "2.06", "1.81", LATEST_CAPTURE),
                row(756L, 135L, "BOOK_A", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", LATEST_CAPTURE),
                row(757L, 135L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.66", "2.29", EARLIER_CAPTURE),
                row(758L, 135L, "BOOK_B", "-0.50", "1.90", "1.90", "2.75", "1.83", "2.07", EARLIER_CAPTURE),
                row(759L, 135L, "BOOK_B", "-0.50", "1.90", "1.90", "3.00", "2.06", "1.81", EARLIER_CAPTURE),
                row(760L, 135L, "BOOK_B", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, crossProductRows, crossProductRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        // 2.75 档去水 0.5308，是四档里唯一离 50/50 最近的一档 → 主盘；754 是该档最新一行。
        assertThat(prediction.asianOddsSnapshotId()).isEqualTo(754L);
        assertThat(prediction.analysisSummary()).contains("大小球 判定线 2.75");
        // 修复前冻结 3.25（去水 0.4121）会判小球，方向与市场相反。
        assertThat(prediction.totalGoalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
        assertThat(prediction.totalGoalsConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
    }

    /**
     * 共识让球线上没有任何一家报出大小球主盘那一档时，宁可跳过也不发一条注定偏的方向。
     *
     * <p>此时判定线无法与反解线对齐，{@code edge_over} 会凭空出现、结算也会按被抬高的线判定。
     * 这是「数据稀疏」而非正常路径，必须留下可诊断的跳过原因，不能静默降级。</p>
     */
    @Test
    void skipsWhenNoSnapshotOnTotalsConsensusLine() {
        MatchEntity match = scheduledMatch(136L);
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(761L, 136L, "BOOK_A", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", LATEST_CAPTURE),
                row(762L, 136L, "BOOK_B", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", EARLIER_CAPTURE));
        // 2.75 那一档只出现在另一档让球线（-0.75）上，因此在 -0.50 上不可冻结。
        List<AsianOddsSnapshot> crossProductRows = List.of(
                row(763L, 136L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.66", "2.29", LATEST_CAPTURE),
                row(764L, 136L, "BOOK_A", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", LATEST_CAPTURE),
                row(765L, 136L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.66", "2.29", EARLIER_CAPTURE),
                row(766L, 136L, "BOOK_B", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", EARLIER_CAPTURE));
        List<AsianOddsSnapshot> totalsRows = List.of(
                row(767L, 136L, "BOOK_A", "-0.75", "1.90", "1.90", "2.75", "1.83", "2.07", LATEST_CAPTURE),
                row(768L, 136L, "BOOK_B", "-0.75", "1.90", "1.90", "2.75", "1.83", "2.07", EARLIER_CAPTURE),
                row(763L, 136L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.66", "2.29", LATEST_CAPTURE),
                row(764L, 136L, "BOOK_A", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", LATEST_CAPTURE),
                row(765L, 136L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.66", "2.29", EARLIER_CAPTURE),
                row(766L, 136L, "BOOK_B", "-0.50", "1.90", "1.90", "3.25", "2.34", "1.64", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, totalsRows, crossProductRows);

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        assertThat(result.generatedCount()).isZero();
        assertThat(result.skippedByReason())
                .containsEntry(BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE, 1);
        verify(predictionImportService, never()).importFile(any(byte[].class));
    }

    @Test
    void skipsWhenTotalsMarketHasFewerThanTwoBookmakers() {
        MatchEntity match = scheduledMatch(104L);
        List<AsianOddsSnapshot> rows = List.of(
                row(801L, 104L, "BOOK_A", "-0.25", "1.98", "1.82", "2.50", "1.95", "1.95", LATEST_CAPTURE),
                row(802L, 104L, "BOOK_B", "-0.25", "1.98", "1.82", "2.50", "1.95", "1.95", EARLIER_CAPTURE));
        // 大小球只有一家报价：第二个约束缺失，一个方程解不出两个未知数。
        stubMarkets(match, rows, List.of(rows.get(0)));

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        // 这里与 v4 的行为不同：v4 会降级为单侧方向，v5 整场跳过，避免同一批次混入没有 λ 的预测。
        verify(predictionImportService, never()).importFile(any());
        assertThat(result.generatedCount()).isZero();
        assertThat(result.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_CONSTRAINTS, 1);
    }

    @Test
    void skipsWhenReverseSolvedLambdasContradictNeighborLines() {
        MatchEntity match = scheduledMatch(110L);
        List<AsianOddsSnapshot> rows = List.of(
                row(1101L, 110L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(1102L, 110L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.80", "2.00", EARLIER_CAPTURE),
                // 邻线 0.00 两家都报 1.55/2.45，去水后主队 0.61；而从主盘反解出的 λ 在该线只能给出 0.50。
                // 偏差 0.11 远超 0.04 阈值：市场在两条线上自相矛盾，主盘残差为 0 也救不了。
                row(1103L, 110L, "BOOK_C", "0.00", "1.55", "2.45", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(1104L, 110L, "BOOK_D", "0.00", "1.55", "2.45", "2.50", "1.80", "2.00", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        verify(predictionImportService, never()).importFile(any());
        assertThat(result.generatedCount()).isZero();
        assertThat(result.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS, 1);
    }

    @Test
    void summaryStaysUnderDatabaseLimit() {
        MatchEntity match = scheduledMatch(111L);
        // 冻结行与主盘同在 2.50，这是修复后的正常形态（判定线必须等于大小球主盘线）。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(1201L, 111L, "BOOK_ALPHA_LONG_NAME", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", LATEST_CAPTURE),
                row(1202L, 111L, "BOOK_BETA_LONG_NAME", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                // 一档合格邻线，让摘要带上「邻线最大偏差 x%（N 档）」；报价须与主盘反解出的 λ 相容。
                row(1203L, 111L, "BOOK_GAMMA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(1204L, 111L, "BOOK_DELTA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        List<AsianOddsSnapshot> totalsRows = List.of(
                row(1301L, 111L, "BOOK_ALPHA_LONG_NAME", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", LATEST_CAPTURE),
                row(1302L, 111L, "BOOK_BETA_LONG_NAME", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(1303L, 111L, "BOOK_GAMMA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(1304L, 111L, "BOOK_DELTA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, totalsRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.analysisSummary())
                .contains("亚盘 λ 反解 v6", "反解 λ 主", "模型概率", "影子门槛")
                .contains("大小球 判定线 2.50")
                .contains("邻线最大偏差");
        // predictions.analysis_summary 是 varchar(1000)，超长会在导入时抛 SQL 异常并整批回滚。
        // 收紧到 900 是为了让阶段 E 再往摘要里加字段时在这里撞墙，而不是在集成测试里整批回滚。
        assertThat(prediction.analysisSummary().length()).isLessThan(900);
    }

    @Test
    void skipsWhenHandicapConsensusHasFewerThanTwoBookmakers() {
        MatchEntity match = scheduledMatch(105L);
        List<AsianOddsSnapshot> single = List.of(
                row(901L, 105L, "BOOK_A", "-0.25", "1.98", "1.82", "2.50", "1.95", "1.95", LATEST_CAPTURE));
        stubMarkets(match, single, single);

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        verify(predictionImportService, never()).importFile(any());
        assertThat(result.generatedCount()).isZero();
        assertThat(result.generationBatchId()).isNull();
        assertThat(result.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS, 1);
    }

    @Test
    void skipsWhenConsensusLineHasNoFreezableRow() {
        MatchEntity match = scheduledMatch(106L);
        // 两家都报 -0.25，但都没有大小球，无法冻结成预测引用的完整快照。
        List<AsianOddsSnapshot> rows = List.of(
                row(1001L, 106L, "BOOK_A", "-0.25", "1.90", "1.90", null, null, null, LATEST_CAPTURE),
                row(1002L, 106L, "BOOK_B", "-0.25", "1.90", "1.90", null, null, null, EARLIER_CAPTURE));
        // 大小球主盘另行存在：否则会先命中 INSUFFICIENT_ASIAN_CONSTRAINTS，测不到冻结这一环。
        List<AsianOddsSnapshot> totalsRows = List.of(
                row(1003L, 106L, "BOOK_A", "-0.25", "1.90", "1.90", "2.50", "1.85", "1.95", LATEST_CAPTURE),
                row(1004L, 106L, "BOOK_B", "-0.25", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, rows, totalsRows);

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        verify(predictionImportService, never()).importFile(any());
        assertThat(result.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE, 1);
    }

    @Test
    void skipsMatchWithoutConfirmedCompleteAsianMarketAndDoesNotImportEmptyBatch() {
        MatchEntity match = scheduledMatch(107L);
        stubMarkets(match, List.of(), List.of());

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        verify(predictionImportService, never()).importFile(any());
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.generatedCount()).isZero();
        assertThat(result.generationBatchId()).isNull();
        assertThat(result.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET, 1);
    }

    @Test
    void keepsNotScheduledAndKickoffPassedOrder() {
        MatchEntity notScheduled = scheduledMatch(108L);
        notScheduled.setMatchStatus(MatchStatusEnum.FINISHED);
        MatchEntity kickoffPassed = scheduledMatch(109L);
        kickoffPassed.setKickoffTime(NOW.minusSeconds(60));
        when(matchMapper.selectPublicDailyMatches(LOTTERY_DATE))
                .thenReturn(List.of(notScheduled, kickoffPassed));

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        assertThat(result.skippedByReason())
                .containsEntry(BaselinePredictionSkipReasonEnum.NOT_SCHEDULED, 1)
                .containsEntry(BaselinePredictionSkipReasonEnum.KICKOFF_PASSED, 1);
    }

    @Test
    void keepsDirectionWhenDispersionEqualsTolerance() {
        MatchEntity match = scheduledMatch(112L);
        // 四家让球 -0.50：两家去水 0.50、两家 0.54 → 均值 0.52，总体标准差恰为 0.020000。
        // 阈值判定必须用「严格大于」，等于上限时放行。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(1401L, 112L, "BOOK_A", "-0.50", "1.90", "1.90", null, null, null, LATEST_CAPTURE),
                row(1402L, 112L, "BOOK_B", "-0.50", "1.90", "1.90", null, null, null, EARLIER_CAPTURE),
                row(1403L, 112L, "BOOK_C", "-0.50", "1.7250", "2.0250", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(1404L, 112L, "BOOK_D", "-0.50", "1.7250", "2.0250", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        List<AsianOddsSnapshot> totalsRows = List.of(handicapRows.get(2), handicapRows.get(3));
        stubMarkets(match, handicapRows, totalsRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
        assertThat(prediction.asianHandicapConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.HIGH);
        assertThat(prediction.analysisSummary())
                .contains("σ 0.0200")
                .doesNotContain("跨庄分歧过大");
        assertThat(meterCount("jingcai.prediction.asian.dispersion",
                "market", "handicap", "outcome", "within", "source", "fallback")).isEqualTo(1d);
    }

    @Test
    void dropsOnlyHandicapWhenDispersionSlightlyExceedsTolerance() {
        MatchEntity match = scheduledMatch(113L);
        // 把 0.540000 换成 0.540541（1.70/2.00）→ σ 0.020271，刚过上限。分市场独立判定：
        // 只砍让球方向，大小球照常输出，且方向与置信度必须成对置空。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(1501L, 113L, "BOOK_A", "-0.50", "1.90", "1.90", null, null, null, LATEST_CAPTURE),
                row(1502L, 113L, "BOOK_B", "-0.50", "1.90", "1.90", null, null, null, EARLIER_CAPTURE),
                row(1503L, 113L, "BOOK_C", "-0.50", "1.70", "2.00", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(1504L, 113L, "BOOK_D", "-0.50", "1.70", "2.00", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        List<AsianOddsSnapshot> totalsRows = List.of(handicapRows.get(2), handicapRows.get(3));
        stubMarkets(match, handicapRows, totalsRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.asianHandicapPick()).isNull();
        assertThat(prediction.asianHandicapConfidenceLevel()).isNull();
        assertThat(prediction.totalGoalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
        assertThat(prediction.totalGoalsConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
        assertThat(prediction.analysisSummary()).contains("方向因跨庄分歧过大不发布");
        assertThat(meterCount("jingcai.prediction.asian.dispersion",
                "market", "handicap", "outcome", "exceeded", "source", "fallback")).isEqualTo(1d);
    }

    @Test
    void rejectsOnlyTotalsWhenHandicapDispersionIsWithinTolerance() {
        MatchEntity match = scheduledMatch(114L);
        // 让球四家完全同价（σ=0，HIGH 置信）；大小球 2.50 档两家 1.80/2.20、两家 2.20/1.80（σ=0.05）。
        // 反向验证分市场独立：这一场是大小球被砍、让球保留。
        List<AsianOddsSnapshot> rows = List.of(
                row(1601L, 114L, "BOOK_A", "-0.50", "1.7250", "2.0250", "2.50", "1.80", "2.20", LATEST_CAPTURE),
                row(1602L, 114L, "BOOK_B", "-0.50", "1.7250", "2.0250", "2.50", "1.80", "2.20", EARLIER_CAPTURE),
                row(1603L, 114L, "BOOK_C", "-0.50", "1.7250", "2.0250", "2.50", "2.20", "1.80", EARLIER_CAPTURE),
                row(1604L, 114L, "BOOK_D", "-0.50", "1.7250", "2.0250", "2.50", "2.20", "1.80", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
        assertThat(prediction.asianHandicapConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.HIGH);
        assertThat(prediction.totalGoalsPick()).isNull();
        assertThat(prediction.totalGoalsConfidenceLevel()).isNull();
    }

    @Test
    void skipsWholeMatchWhenBothMarketsExceedDispersion() {
        MatchEntity match = scheduledMatch(115L);
        // 两个市场都是 0.55 / 0.45 各半 → σ 均为 0.05，两个方向都没了。
        List<AsianOddsSnapshot> rows = List.of(
                row(1701L, 115L, "BOOK_A", "-0.50", "1.80", "2.20", "2.50", "1.80", "2.20", LATEST_CAPTURE),
                row(1702L, 115L, "BOOK_B", "-0.50", "1.80", "2.20", "2.50", "1.80", "2.20", EARLIER_CAPTURE),
                row(1703L, 115L, "BOOK_C", "-0.50", "2.20", "1.80", "2.50", "2.20", "1.80", EARLIER_CAPTURE),
                row(1704L, 115L, "BOOK_D", "-0.50", "2.20", "1.80", "2.50", "2.20", "1.80", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        verify(predictionImportService, never()).importFile(any());
        assertThat(result.generatedCount()).isZero();
        assertThat(result.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.MARKET_DISPERSION_TOO_HIGH, 1);
    }

    @Test
    void prefersConfidenceReasonWhenOnlyOneMarketIsRejectedByDispersion() {
        MatchEntity match = scheduledMatch(117L);
        // 让球对称 1.90/1.90 → 置信度 LOW（没有方向，但不是分歧问题）；大小球 σ=0.05 超限。
        // 两个方向都没了，但归因必须是低置信度，否则阈值调参会把低置信场次算进分歧率。
        List<AsianOddsSnapshot> rows = List.of(
                row(1901L, 117L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.80", "2.20", LATEST_CAPTURE),
                row(1902L, 117L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.80", "2.20", EARLIER_CAPTURE),
                row(1903L, 117L, "BOOK_C", "-0.50", "1.90", "1.90", "2.50", "2.20", "1.80", EARLIER_CAPTURE),
                row(1904L, 117L, "BOOK_D", "-0.50", "1.90", "1.90", "2.50", "2.20", "1.80", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        verify(predictionImportService, never()).importFile(any());
        assertThat(result.skippedByReason())
                .containsEntry(BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS, 1)
                .doesNotContainKey(BaselinePredictionSkipReasonEnum.MARKET_DISPERSION_TOO_HIGH);
    }

    @Test
    void shadowGateRecordsFailureWithoutBlockingGeneration() {
        MatchEntity match = scheduledMatch(116L);
        // 1.85/1.95 去水 0.513158 → MEDIUM，两个市场都有方向；门槛必然不通过但仍要生成草稿。
        List<AsianOddsSnapshot> rows = List.of(
                row(1801L, 116L, "BOOK_A", "-0.50", "1.85", "1.95", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(1802L, 116L, "BOOK_B", "-0.50", "1.85", "1.95", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);
        stubImport();

        BaselinePredictionGenerationVo result = service.generateAndImport(LOTTERY_DATE, "admin");

        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(gateCount("handicap", "fail")).isEqualTo(1d);
        assertThat(gateCount("totals", "fail")).isEqualTo(1d);
        assertThat(gateCount("handicap", "pass")).isZero();
        // 让球的 edge 结构性恒为 0：λ 由该主盘反解，再投影回同一条线必然相等。
        assertThat(meterRegistry.get("jingcai.prediction.asian.gate.edge")
                .tag("market", "handicap").summary().totalAmount()).isZero();
    }

    /**
     * 大小球的 edge 现在也结构性恒为 0，与让球同构。
     *
     * <p>修复前它唯一的非零来源就是「冻结行的大小球线不是主盘」——λ 在 2.50 反解、投影到 3.00，
     * 与市场共识自然不相等，于是 {@code edge_over} 记录下一段形状偏差，看起来像 alpha。
     * 判定线被强制对齐主盘后这条路径消失：投影回的就是反解线，模型概率与共识概率逐位相等。</p>
     */
    @Test
    void totalsEdgeIsStructurallyZeroBecauseJudgmentLineIsAlwaysMainLine() {
        MatchEntity match = scheduledMatch(119L);
        // 复刻真实交叉积：-0.50 这一档同时报了 2.50 与 3.00 两档大小球，但聚合取数按让球线去重
        // 只剩 3.00 那一行（id 更大）。大小球主盘是更平衡的 2.50。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(2201L, 119L, "BOOK_A", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", LATEST_CAPTURE),
                row(2202L, 119L, "BOOK_B", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", EARLIER_CAPTURE),
                row(2203L, 119L, "BOOK_C", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(2204L, 119L, "BOOK_D", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        List<AsianOddsSnapshot> totalsRows = List.of(
                row(2205L, 119L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", LATEST_CAPTURE),
                row(2201L, 119L, "BOOK_A", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", LATEST_CAPTURE),
                row(2206L, 119L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(2202L, 119L, "BOOK_B", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", EARLIER_CAPTURE),
                row(2203L, 119L, "BOOK_C", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(2204L, 119L, "BOOK_D", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        List<AsianOddsSnapshot> crossProductRows = List.of(
                row(2205L, 119L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", LATEST_CAPTURE),
                row(2201L, 119L, "BOOK_A", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", LATEST_CAPTURE),
                row(2206L, 119L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(2202L, 119L, "BOOK_B", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", EARLIER_CAPTURE),
                row(2203L, 119L, "BOOK_C", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(2204L, 119L, "BOOK_D", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, totalsRows, crossProductRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        // 冻结行落在 2.50 那一档（修复前会落在 3.00），方向随之为大球。
        assertThat(capturedPrediction().asianOddsSnapshotId()).isEqualTo(2205L);
        assertThat(capturedPrediction().totalGoalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
        // 投影回的就是反解线，edge 恒 0——修复前那一批 0.78% 的「偏差」纯粹是线位偏移。
        assertThat(meterRegistry.get("jingcai.prediction.asian.gate.edge")
                .tag("market", "totals").summary().totalAmount()).isZero();
    }

    @Test
    void summaryContainsShadowGateWordingWithoutPromiseLanguage() {
        MatchEntity match = scheduledMatch(120L);
        List<AsianOddsSnapshot> rows = List.of(
                row(2301L, 120L, "BOOK_A", "-0.50", "1.85", "1.95", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(2302L, 120L, "BOOK_B", "-0.50", "1.85", "1.95", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        assertThat(capturedPrediction().analysisSummary())
                .contains("edge ", "盈亏余量", "影子门槛", "本版本不据其筛选")
                // 导入侧的禁词表命中即整批回滚，这里做回归守卫。
                .doesNotContain("稳赚", "无风险", "保本", "包赢", "必赚");
    }

    private double gateCount(String market, String outcome) {
        // 用 find 而不是 get：某个 outcome 没有样本时 get 会抛 MeterNotFoundException，
        // 而「通过数为 0」本身就是本阶段要断言的事实。
        var counter = meterRegistry.find("jingcai.prediction.asian.gate")
                .tags("market", market, "outcome", outcome).counter();
        return counter == null ? 0d : counter.count();
    }

    private double meterCount(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0d : counter.count();
    }

    /**
     * 差分测试：同一条夹具分别走「生产 service」与「决策引擎」，逐字段必须相等。
     *
     * <p>这是 P1 重构的核心保证——service 现在只是取数 + 组装，判定全部由引擎完成；若哪天有人把
     * 某条规则又写回 service，这个断言会立刻失败。数值一律 {@code isEqualByComparingTo}：两边都
     * 走同一个 6 位标度的缩放，再用 {@code isCloseTo} 会掩盖真正的末位漂移。</p>
     */
    @Test
    void engineReproducesProductionDecisionFieldByField() {
        MatchEntity match = scheduledMatch(101L);
        List<AsianOddsSnapshot> rows = List.of(
                row(501L, 101L, "BOOK_A", "-0.50", "1.90", "1.90", "2.50", "1.80", "2.00", LATEST_CAPTURE),
                row(502L, 101L, "BOOK_B", "-0.50", "1.90", "1.90", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");
        PredictionImportDto fromService = capturedPrediction();

        AsianDecision fromEngine = new AsianDecisionEngine(
                AsianOddsAggregationProperties.defaults(), AsianModelProperties.defaults(), meterRegistry)
                .decide(new AsianDecisionInput(
                        101L,
                        MatchStatusEnum.SCHEDULED,
                        match.getKickoffTime(),
                        NOW,
                        rows,
                        rows.stream()
                                .map(row -> new com.jingcaicompass.odds.service.AsianMarketConsensusAggregator.Quote(
                                        row.getProviderCode(), row.getBookmakerCode(), row.getTotalLine(),
                                        row.getOverOdds(), row.getUnderOdds(), row.getCapturedAt(), row.getId()))
                                .toList(),
                        rows));

        assertThat(fromEngine.skipReason()).isNull();
        assertThat(fromService.asianOddsSnapshotId()).isEqualTo(fromEngine.frozen().getId());
        assertThat(fromService.generatedAt()).isEqualTo(fromEngine.featureAsOf());
        assertThat(fromService.asianHandicapPick()).isEqualTo(fromEngine.handicap().pick());
        assertThat(fromService.totalGoalsPick()).isEqualTo(fromEngine.totals().pick());
        // 落库口径是「没方向就没有置信度」，与引擎内部的 LOW 保留不同，必须按同一规则比较。
        assertThat(fromService.asianHandicapConfidenceLevel()).isEqualTo(
                fromEngine.handicap().pick() == null ? null : fromEngine.handicap().confidence());
        assertThat(fromService.totalGoalsConfidenceLevel()).isEqualTo(
                fromEngine.totals().pick() == null ? null : fromEngine.totals().confidence());

        AsianModelOutputDto model = fromService.asianModel();
        // BigDecimal 用 isEqualByComparingTo 而不是 isEqualTo：DTO 是从序列化文件里解析回来的，
        // JSON 会吃掉尾部零（0.500000 → 0.5），按 scale 比较只会测到这条序列化细节。
        assertThat(model.lambdaHome()).isEqualByComparingTo(scale(fromEngine.solution().lambdaHome()));
        assertThat(model.lambdaAway()).isEqualByComparingTo(scale(fromEngine.solution().lambdaAway()));
        assertThat(model.modelCoverProb()).isEqualByComparingTo(scale(fromEngine.handicap().coverProbability()));
        assertThat(model.marketCoverProb()).isEqualByComparingTo(fromEngine.handicapConsensus().firstProbability());
        assertThat(model.edgeCover()).isEqualByComparingTo(
                scale(fromEngine.handicap().coverProbability()).subtract(fromEngine.handicapConsensus().firstProbability()));
        assertThat(model.modelOverProb()).isEqualByComparingTo(scale(fromEngine.totals().overProbability()));
        assertThat(model.marketOverProb()).isEqualByComparingTo(fromEngine.totalsAtFrozenLine().firstProbability());
        assertThat(model.edgeOver()).isEqualByComparingTo(
                scale(fromEngine.totals().overProbability()).subtract(fromEngine.totalsAtFrozenLine().firstProbability()));
        assertThat(model.handicapDispersion()).isEqualByComparingTo(fromEngine.handicapConsensus().dispersion());
        assertThat(model.totalsDispersion()).isEqualByComparingTo(fromEngine.totalsAtFrozenLine().dispersion());
        // 主盘上 edge 恒为 0：λ 由该盘口反解、再投影回同一条线，必然复现市场概率。
        assertThat(model.edgeCover()).isEqualByComparingTo("0.000000");
    }

    /** 与落库标度一致（6 位、HALF_UP），用于把引擎的 double 结果与 DTO 的 BigDecimal 对齐。 */
    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private void stubMarkets(
            MatchEntity match,
            List<AsianOddsSnapshot> handicapRows,
            List<AsianOddsSnapshot> totalsRows
    ) {
        stubMarkets(match, handicapRows, totalsRows, handicapRows);
    }

    /**
     * @param handicapRows 聚合取数结果：每家每档让球线一行（交叉积已被压成单档大小球）
     * @param freezeRows   冻结行候选：完整交叉积，每家 × 每档让球线 × 每档大小球线
     */
    private void stubMarkets(
            MatchEntity match,
            List<AsianOddsSnapshot> handicapRows,
            List<AsianOddsSnapshot> totalsRows,
            List<AsianOddsSnapshot> freezeRows
    ) {
        when(matchMapper.selectPublicDailyMatches(LOTTERY_DATE)).thenReturn(List.of(match));
        when(asianOddsSnapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(match.getId()), "NOVA_API"))
                .thenReturn(handicapRows);
        when(asianOddsSnapshotMapper.selectLatestTotalsLinesByMatchIds(List.of(match.getId()), "NOVA_API"))
                .thenReturn(totalsRows);
        when(asianOddsSnapshotMapper.selectLatestCrossProductLinesByMatchIds(List.of(match.getId()), "NOVA_API"))
                .thenReturn(freezeRows);
    }

    private void stubImport() {
        when(predictionImportService.importFile(any())).thenAnswer(invocation -> {
            PredictionImportBatchDto batch = parser.parse(invocation.getArgument(0));
            return new PredictionImportResultDto(
                    batch.generationBatchId(),
                    batch.generationBatchHash(),
                    1,
                    1,
                    0,
                    List.of(9001L)
            );
        });
    }

    private PredictionImportDto capturedPrediction() {
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(predictionImportService).importFile(contentCaptor.capture());
        return parser.parse(contentCaptor.getValue()).predictions().get(0);
    }

    private MatchEntity scheduledMatch(long id) {
        MatchEntity match = new MatchEntity();
        match.setId(id);
        match.setMatchStatus(MatchStatusEnum.SCHEDULED);
        match.setKickoffTime(NOW.plusSeconds(86_400));
        return match;
    }

    private AsianOddsSnapshot row(
            Long id,
            Long matchId,
            String bookmaker,
            String handicapLine,
            String homeOdds,
            String awayOdds,
            String totalLine,
            String overOdds,
            String underOdds,
            Instant capturedAt
    ) {
        AsianOddsSnapshot snapshot = new AsianOddsSnapshot();
        snapshot.setId(id);
        snapshot.setMatchId(matchId);
        snapshot.setProviderCode("ASIAN_TEST");
        snapshot.setBookmakerCode(bookmaker);
        snapshot.setHandicapLine(new BigDecimal(handicapLine));
        snapshot.setHomeOdds(new BigDecimal(homeOdds));
        snapshot.setAwayOdds(new BigDecimal(awayOdds));
        snapshot.setTotalLine(totalLine == null ? null : new BigDecimal(totalLine));
        snapshot.setOverOdds(overOdds == null ? null : new BigDecimal(overOdds));
        snapshot.setUnderOdds(underOdds == null ? null : new BigDecimal(underOdds));
        snapshot.setCapturedAt(capturedAt);
        return snapshot;
    }
}
