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
            assertThat(prediction.modelVersion()).isEqualTo("t306-asian-baseline-v4");
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
                    .contains("亚盘共识基线 v4", "让球主盘 -0.50", "回退全量 2 家", "冻结报价 1.90/1.90",
                            "不代表独立校准概率");
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
    void derivesDirectionFromConsensusAndConfidenceFromFrozenRow() {
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
        // 共识 0.5433 偏向主队；冻结行自身却偏向客队 —— 方向必须跟共识走。
        assertThat(prediction.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
        assertThat(prediction.asianHandicapConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
        assertThat(prediction.totalGoalsPick()).isNull();
        assertThat(prediction.analysisSummary()).contains("共识概率 主 0.5433");
    }

    @Test
    void dropsTotalsDirectionWhenFrozenLineHasTooFewQuotes() {
        MatchEntity match = scheduledMatch(104L);
        List<AsianOddsSnapshot> rows = List.of(
                row(801L, 104L, "BOOK_A", "-0.25", "1.98", "1.82", "2.50", "1.95", "1.95", LATEST_CAPTURE),
                row(802L, 104L, "BOOK_B", "-0.25", "1.98", "1.82", "2.50", "1.95", "1.95", EARLIER_CAPTURE));
        // 大小球只有一家报价：无法形成共识，只能降级为单侧方向，不新增跳过原因。
        stubMarkets(match, rows, List.of(rows.get(0)));
        stubImport();

        service.generateAndImport(LOTTERY_DATE, "admin");

        PredictionImportDto prediction = capturedPrediction();
        // 两家都偏向客队，方向跟随共识为客队赢盘；大小球因样本不足直接不生成方向。
        assertThat(prediction.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.AWAY_COVER);
        assertThat(prediction.totalGoalsPick()).isNull();
        assertThat(prediction.analysisSummary()).contains("大小球该判定线聚合家数不足");
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
        stubMarkets(match, rows, List.of());

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
