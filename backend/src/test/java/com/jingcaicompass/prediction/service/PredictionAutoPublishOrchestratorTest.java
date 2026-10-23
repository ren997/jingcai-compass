package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import com.jingcaicompass.prediction.vo.PredictionAutoPublishVo;
import com.jingcaicompass.prediction.vo.PredictionBatchPublishResultVo;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 自动发布编排的收敛规则。
 *
 * <p>这里守的都是「看起来成功了但其实不对」的失败模式：重复生成、重复发布、失败原因没落库。
 * 它们不会抛异常，只会在几天后表现为「同一场有十几个 DRAFT」或者「公开版本号莫名涨到 v7」。</p>
 */
class PredictionAutoPublishOrchestratorTest {

    private static final LocalDate LOTTERY_DATE = LocalDate.of(2026, 10, 22);
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OPERATOR = "system:auto-publish";

    private BaselinePredictionGenerationService generationService;
    private PredictionBatchPublishService batchPublishService;
    private PredictionMapper predictionMapper;
    private PredictionAutoPublishOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        generationService = mock(BaselinePredictionGenerationService.class);
        batchPublishService = mock(PredictionBatchPublishService.class);
        predictionMapper = mock(PredictionMapper.class);
        orchestrator = orchestrator(Duration.ofHours(3), OPERATOR);
    }

    @Test
    void skipsWholeRoundWhenEveryMatchAlreadyHasPublicPrediction() {
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(0L);

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        assertThat(result.skippedReason()).isEqualTo(PredictionAutoPublishVo.SKIP_NO_PENDING_MATCH);
        assertThat(result.status()).isEqualTo("SKIPPED");
        // 短路必须连生成都不做：生成不幂等，多跑一次就多一批 DRAFT
        verifyNoInteractions(generationService, batchPublishService);
    }

    @Test
    void throttlesRegenerationInsideIntervalButStillPublishesExistingDrafts() {
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(3L);
        when(predictionMapper.selectLatestGeneratedAtByLotteryDate(LOTTERY_DATE))
                .thenReturn(NOW.minus(Duration.ofMinutes(30)));
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW))
                .thenReturn(List.of(draft(11L)));
        when(batchPublishService.publish(any(), eq(OPERATOR))).thenReturn(published(11L));

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        assertThat(result.generationPerformed()).isFalse();
        assertThat(result.generatedCount()).isZero();
        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(result.skippedReason()).isNull();
        verify(generationService, never()).generateAndImport(any(), any());
    }

    @Test
    void generatesOnceWindowElapsedThenPublishesCandidates() {
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(2L);
        when(predictionMapper.selectLatestGeneratedAtByLotteryDate(LOTTERY_DATE))
                .thenReturn(NOW.minus(Duration.ofHours(4)));
        when(generationService.generateAndImport(LOTTERY_DATE, OPERATOR)).thenReturn(generation(2, 2));
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW))
                .thenReturn(List.of(draft(1L), draft(2L)));
        when(batchPublishService.publish(any(), eq(OPERATOR))).thenReturn(published(1L, 2L));

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        assertThat(result.generationPerformed()).isTrue();
        assertThat(result.generatedCount()).isEqualTo(2);
        assertThat(result.insertedCount()).isEqualTo(2);
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.publishedCount()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("SUCCESS");
    }

    @Test
    void regeneratesEveryRoundWhenIntervalIsZero() {
        orchestrator = orchestrator(Duration.ZERO, OPERATOR);
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(1L);
        when(generationService.generateAndImport(LOTTERY_DATE, OPERATOR)).thenReturn(generation(1, 1));
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW))
                .thenReturn(List.of(draft(7L)));
        when(batchPublishService.publish(any(), eq(OPERATOR))).thenReturn(published(7L));

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        assertThat(result.generationPerformed()).isTrue();
        // 间隔为 0 时不该再去查上次生成时间
        verify(predictionMapper, never()).selectLatestGeneratedAtByLotteryDate(any());
    }

    @Test
    void recordsPublishFailureIntoTheRowSoTheReasonSurvivesReruns() {
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(2L);
        when(predictionMapper.selectLatestGeneratedAtByLotteryDate(LOTTERY_DATE)).thenReturn(NOW);
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW))
                .thenReturn(List.of(draft(1L), draft(2L)));
        when(batchPublishService.publish(any(), eq(OPERATOR)))
                .thenReturn(mixed(1L, 2L, "prediction publish deadline has passed: 2"));

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.failures()).containsEntry(2L, "prediction publish deadline has passed: 2");
        assertThat(result.status()).isEqualTo("PARTIAL");
        verify(predictionMapper).recordPublishFailure(2L, "prediction publish deadline has passed: 2");
        verify(predictionMapper, never()).recordPublishFailure(eq(1L), any());
    }

    @Test
    void truncatesFailureReasonToColumnWidth() {
        String longReason = "x".repeat(400);
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(1L);
        when(predictionMapper.selectLatestGeneratedAtByLotteryDate(LOTTERY_DATE)).thenReturn(NOW);
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW))
                .thenReturn(List.of(draft(9L)));
        when(batchPublishService.publish(any(), eq(OPERATOR))).thenReturn(mixed(8L, 9L, longReason));

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        // last_publish_error 是 VARCHAR(200)，超长会直接让更新语句报错
        assertThat(result.failures().get(9L)).hasSize(200);
        verify(predictionMapper).recordPublishFailure(9L, "x".repeat(200));
    }

    @Test
    void reportsNoCandidateWithoutCallingPublish() {
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(4L);
        when(predictionMapper.selectLatestGeneratedAtByLotteryDate(LOTTERY_DATE)).thenReturn(NOW);
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW)).thenReturn(List.of());

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        assertThat(result.skippedReason()).isEqualTo(PredictionAutoPublishVo.SKIP_NO_CANDIDATE);
        assertThat(result.pendingMatchCount()).isEqualTo(4);
        verify(batchPublishService, never()).publish(any(), any());
    }

    @Test
    void fallsBackToSystemOperatorWhenConfiguredOperatorIsBlank() {
        orchestrator = orchestrator(Duration.ZERO, "  ");
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(1L);
        when(generationService.generateAndImport(LOTTERY_DATE, OPERATOR)).thenReturn(generation(1, 1));
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW))
                .thenReturn(List.of(draft(5L)));
        when(batchPublishService.publish(any(), eq(OPERATOR))).thenReturn(published(5L));

        orchestrator.run(LOTTERY_DATE);

        // 发布服务会拒绝空操作者，配置漏填时不能把整轮发布打成全失败
        verify(batchPublishService).publish(any(), eq(OPERATOR));
    }

    @Test
    void countsReusedPublishSeparatelyFromFreshOnes() {
        when(predictionMapper.countUnpublishedMatchesByLotteryDate(LOTTERY_DATE, NOW)).thenReturn(1L);
        when(predictionMapper.selectLatestGeneratedAtByLotteryDate(LOTTERY_DATE)).thenReturn(NOW);
        when(predictionMapper.selectAutoPublishCandidateDrafts(LOTTERY_DATE, NOW))
                .thenReturn(List.of(draft(3L)));
        when(batchPublishService.publish(any(), eq(OPERATOR))).thenReturn(reused(3L));

        PredictionAutoPublishVo result = orchestrator.run(LOTTERY_DATE);

        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(result.reusedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
    }

    private PredictionAutoPublishOrchestrator orchestrator(Duration regenerateInterval, String operator) {
        return new PredictionAutoPublishOrchestrator(
                generationService,
                batchPublishService,
                predictionMapper,
                CLOCK,
                regenerateInterval,
                operator
        );
    }

    private static Prediction draft(Long predictionId) {
        Prediction prediction = new Prediction();
        prediction.setId(predictionId);
        prediction.setMatchId(predictionId);
        prediction.setModelVersion("v5");
        prediction.setPredictionVersion(1);
        return prediction;
    }

    private static BaselinePredictionGenerationVo generation(int generated, int inserted) {
        return new BaselinePredictionGenerationVo(
                LOTTERY_DATE,
                "v5",
                "asian-v1",
                generated,
                generated,
                inserted,
                generated - inserted,
                "batch-20261022",
                "hash-20261022",
                NOW,
                Map.of()
        );
    }

    private static PredictionBatchPublishResultVo published(Long... predictionIds) {
        List<PredictionBatchPublishResultVo.Item> items = new ArrayList<>();
        for (Long predictionId : predictionIds) {
            items.add(item(predictionId, false));
        }
        return new PredictionBatchPublishResultVo(predictionIds.length, predictionIds.length, 0, items);
    }

    private static PredictionBatchPublishResultVo reused(Long predictionId) {
        return new PredictionBatchPublishResultVo(
                1,
                1,
                0,
                List.of(item(predictionId, true))
        );
    }

    private static PredictionBatchPublishResultVo mixed(Long successId, Long failedId, String error) {
        return new PredictionBatchPublishResultVo(
                2,
                1,
                1,
                List.of(
                        item(successId, false),
                        PredictionBatchPublishResultVo.Item.failure(failedId, failedId, error)
                )
        );
    }

    private static PredictionBatchPublishResultVo.Item item(Long predictionId, boolean reused) {
        return PredictionBatchPublishResultVo.Item.success(new PredictionPublishResultVo(
                predictionId,
                predictionId,
                "v5",
                1,
                PredictionStatusEnum.PUBLISHED,
                NOW,
                NOW.plus(Duration.ofHours(2)),
                "hash",
                reused
        ));
    }
}
