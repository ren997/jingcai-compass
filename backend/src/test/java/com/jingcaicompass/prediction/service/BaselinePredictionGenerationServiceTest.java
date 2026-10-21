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

    private PredictionImportFileParser parser;
    private BaselinePredictionGenerationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        parser = new PredictionImportFileParserImpl(objectMapper);
        service = new BaselinePredictionGenerationServiceImpl(
                matchMapper,
                sportteryPoolSnapshotMapper,
                asianOddsSnapshotMapper,
                predictionMapper,
                predictionImportService,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                AsianOddsAggregationProperties.defaults(),
                AsianModelProperties.defaults(),
                new SimpleMeterRegistry()
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
            assertThat(prediction.modelVersion()).isEqualTo("t306-asian-baseline-v5");
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
                    .contains("亚盘 λ 反解 v5", "让球主盘 -0.50", "回退全量 2 家", "冻结报价 1.90/1.90",
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
        List<AsianOddsSnapshot> rows = List.of(
                // 可冻结的两家都偏向客队（去水概率 < 0.5），但只有 LOW 置信度才不出方向。
                row(701L, 103L, "BOOK_A", "-0.25", "1.98", "1.82", "2.50", "1.95", "1.95", LATEST_CAPTURE),
                row(702L, 103L, "BOOK_B", "-0.25", "1.98", "1.82", "2.50", "1.95", "1.95", EARLIER_CAPTURE),
                // 大小球残缺的行不参与冻结，但参与让球共识，把中位数拉到主队一侧。
                row(703L, 103L, "BOOK_C", "-0.25", "1.55", "2.40", null, null, null, EARLIER_CAPTURE),
                row(704L, 103L, "BOOK_D", "-0.25", "1.50", "2.45", null, null, null, EARLIER_CAPTURE));
        stubMarkets(match, rows, rows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        // 共识 0.5433 偏向主队；冻结行自身却偏向客队 —— 方向必须跟模型概率走。
        assertThat(prediction.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
        assertThat(prediction.asianHandicapConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
        assertThat(prediction.totalGoalsPick()).isNull();
        assertThat(prediction.analysisSummary())
                .contains("共识概率 主 0.5433")
                // λ 是在主盘上反解出来的，回投必然复现共识，因此模型概率与共识概率在小数点后四位一致。
                .contains("模型概率 0.5433");
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
        // 冻结行的大小球线是 3.00，而大小球主盘在更平衡的 2.50 —— 摘要要多写一句「该行未报主盘线」，
        // 这是最坏情况下的文本长度来源之一。
        List<AsianOddsSnapshot> handicapRows = List.of(
                row(1201L, 111L, "BOOK_ALPHA_LONG_NAME", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", LATEST_CAPTURE),
                row(1202L, 111L, "BOOK_BETA_LONG_NAME", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", EARLIER_CAPTURE),
                // 一档合格邻线，让摘要带上「邻线最大偏差 x%（N 档）」；报价须与主盘反解出的 λ 相容。
                row(1203L, 111L, "BOOK_GAMMA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(1204L, 111L, "BOOK_DELTA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        // 同一批行的大小球投影：-0.50 那两家报 3.00，-0.75 那两家报 2.50，主盘落在更平衡的 2.50。
        // 3.00 档的去水概率必须与反解出的 λ 相容（偏差在 0.04 内），否则整场会先被判不自洽。
        List<AsianOddsSnapshot> totalsRows = List.of(
                row(1301L, 111L, "BOOK_ALPHA_LONG_NAME", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", LATEST_CAPTURE),
                row(1302L, 111L, "BOOK_BETA_LONG_NAME", "-0.50", "1.90", "1.90", "3.00", "2.50", "1.55", EARLIER_CAPTURE),
                row(1303L, 111L, "BOOK_GAMMA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE),
                row(1304L, 111L, "BOOK_DELTA_LONG_NAME", "-0.75", "2.26", "1.73", "2.50", "1.85", "1.95", EARLIER_CAPTURE));
        stubMarkets(match, handicapRows, totalsRows);
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        assertThat(prediction.analysisSummary())
                .contains("亚盘 λ 反解 v5", "反解 λ 主", "模型概率")
                .contains("大小球主盘 2.50，该行未报主盘线")
                .contains("邻线最大偏差");
        // predictions.analysis_summary 是 varchar(1000)，超长会在导入时抛 SQL 异常并整批回滚。
        assertThat(prediction.analysisSummary().length()).isLessThan(1000);
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

    private void stubMarkets(
            MatchEntity match,
            List<AsianOddsSnapshot> handicapRows,
            List<AsianOddsSnapshot> totalsRows
    ) {
        when(matchMapper.selectPublicDailyMatches(LOTTERY_DATE)).thenReturn(List.of(match));
        when(asianOddsSnapshotMapper.selectLatestConfirmedHandicapLinesByMatchIds(List.of(match.getId())))
                .thenReturn(handicapRows);
        when(asianOddsSnapshotMapper.selectLatestConfirmedTotalsLinesByMatchIds(List.of(match.getId())))
                .thenReturn(totalsRows);
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
