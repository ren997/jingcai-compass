package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionBatchPublishDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import com.jingcaicompass.prediction.vo.PredictionAutoPublishVo;
import com.jingcaicompass.prediction.vo.PredictionBatchPublishResultVo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 「生成 → 发布」的自动编排。
 *
 * <p><b>为什么本类不加 {@code @Transactional}</b>：{@code generateAndImport} 自带事务，
 * 若在同一条事务里接着调 publish，publish 的 {@code @Transactional(REQUIRED)} 会加入外层事务，
 * 批次内逐条 catch 的异常仍可能把外层标记 rollback-only，最后整批回滚。编排层不参与事务，
 * 两次调用各自独立提交。</p>
 *
 * <p><b>为什么需要生成节流</b>：生成不幂等——{@code generationBatchHash} 含 {@code generatedAt}，
 * 重跑必然插入新批次。若每轮调度都生成，一天会产出上百条同场 DRAFT。因此只在「当天从未生成过」
 * 或「距上次生成超过重新生成间隔」时才生成，其余轮次只做发布。</p>
 *
 * <p><b>发布范围为什么必须收敛</b>：赔率每刷新一轮就产生新批次，同一场会出现 v2/v3。发布候选
 * 因此限定为「每场每模型版本号最小的那条 DRAFT，且该组合还没有公开版本」，否则公开版本会随着
 * 赔率抖动无限翻新，历史可追溯性直接失效。</p>
 */
public class PredictionAutoPublishOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PredictionAutoPublishOrchestrator.class);

    /** 与 {@code predictions.last_publish_error VARCHAR(200)} 对齐。 */
    private static final int MAX_ERROR_LENGTH = 200;

    private final BaselinePredictionGenerationService generationService;
    private final PredictionBatchPublishService batchPublishService;
    private final PredictionMapper predictionMapper;
    private final Clock clock;
    private final Duration regenerateInterval;
    private final String operator;

    public PredictionAutoPublishOrchestrator(
            BaselinePredictionGenerationService generationService,
            PredictionBatchPublishService batchPublishService,
            PredictionMapper predictionMapper,
            Clock clock,
            Duration regenerateInterval,
            String operator
    ) {
        this.generationService = generationService;
        this.batchPublishService = batchPublishService;
        this.predictionMapper = predictionMapper;
        this.clock = clock;
        this.regenerateInterval = regenerateInterval == null ? Duration.ZERO : regenerateInterval;
        this.operator = StringUtils.hasText(operator) ? operator.trim() : "system:auto-publish";
    }

    /**
     * 对指定竞彩业务日执行一轮自动发布。
     *
     * @param lotteryDate 竞彩业务日（Asia/Shanghai）
     * @return 本轮摘要；短路时 {@code skippedReason} 非空且未触发任何写操作
     */
    public PredictionAutoPublishVo run(LocalDate lotteryDate) {
        LocalDate targetDate = Objects.requireNonNull(lotteryDate, "lotteryDate must not be null");
        Instant startedAt = now();

        // 1) 短路：当天开赛时间未到的场次是否都已有公开预测。
        //    没有这一步，每轮调度都会像开工一样重新生成一批 DRAFT。
        long pendingMatchCount = predictionMapper.countUnpublishedMatchesByLotteryDate(targetDate, startedAt);
        if (pendingMatchCount == 0) {
            log.info("event=prediction_auto_publish_skipped lotteryDate={} reason={}",
                    targetDate, PredictionAutoPublishVo.SKIP_NO_PENDING_MATCH);
            return skipped(targetDate, 0, false, 0, 0, PredictionAutoPublishVo.SKIP_NO_PENDING_MATCH);
        }

        // 2) 生成（受间隔节流）。生成失败的异常直接上抛，交给任务层记 FAILED：
        //    没有新 DRAFT 时继续发布旧候选是合理的，但把它记成 SUCCESS 会掩盖数据源故障。
        boolean generationPerformed = shouldRegenerate(targetDate, startedAt);
        int generatedCount = 0;
        int insertedCount = 0;
        if (generationPerformed) {
            BaselinePredictionGenerationVo generation = generationService.generateAndImport(targetDate, operator);
            generatedCount = generation.generatedCount();
            insertedCount = generation.insertedCount();
            log.info(
                    "event=prediction_auto_publish_generated lotteryDate={} candidates={} generated={} inserted={} reused={}",
                    targetDate,
                    generation.candidateCount(),
                    generatedCount,
                    insertedCount,
                    generation.reusedCount()
            );
        }

        // 3) 候选：生成可能耗时几十秒，重新取一次时间，避免把刚开赛的场次放进候选
        Instant candidateAt = now();
        List<Prediction> candidates = predictionMapper.selectAutoPublishCandidateDrafts(targetDate, candidateAt);
        if (candidates.isEmpty()) {
            log.info(
                    "event=prediction_auto_publish_skipped lotteryDate={} reason={} pendingMatches={} generated={}",
                    targetDate,
                    PredictionAutoPublishVo.SKIP_NO_CANDIDATE,
                    pendingMatchCount,
                    generationPerformed
            );
            return skipped(
                    targetDate,
                    pendingMatchCount,
                    generationPerformed,
                    generatedCount,
                    insertedCount,
                    PredictionAutoPublishVo.SKIP_NO_CANDIDATE
            );
        }

        // 4) 交给批量发布：内部按 (matchId, predictionId) 升序串行、逐条独立事务
        List<Long> candidateIds = candidates.stream().map(Prediction::getId).toList();
        PredictionBatchPublishResultVo publishResult = batchPublishService.publish(
                new PredictionBatchPublishDto(candidateIds),
                operator
        );

        // 5) 逐条记录失败痕迹。失败原因必须落库：自动发布每轮都会重扫，
        //    只留在日志里等于每轮都要重新翻一遍。
        Map<Long, String> failures = new LinkedHashMap<>();
        int reusedCount = 0;
        for (PredictionBatchPublishResultVo.Item item : publishResult.results()) {
            if (item.success()) {
                if (item.reused()) {
                    reusedCount++;
                }
                continue;
            }
            String error = truncate(item.errorMessage());
            failures.put(item.predictionId(), error);
            recordFailure(item.predictionId(), error);
        }

        log.info(
                "event=prediction_auto_publish_finished lotteryDate={} pendingMatches={} generated={} inserted={} "
                        + "candidates={} published={} reused={} failed={}",
                targetDate,
                pendingMatchCount,
                generatedCount,
                insertedCount,
                candidateIds.size(),
                publishResult.successCount(),
                reusedCount,
                publishResult.failureCount()
        );
        return new PredictionAutoPublishVo(
                targetDate,
                pendingMatchCount,
                generationPerformed,
                generatedCount,
                insertedCount,
                candidateIds.size(),
                publishResult.successCount(),
                reusedCount,
                publishResult.failureCount(),
                null,
                failures
        );
    }

    /**
     * 是否允许本轮重新生成。
     *
     * <p>间隔为 0 表示不节流（等价于每轮都生成），留给需要高频重算的场景显式打开。</p>
     */
    private boolean shouldRegenerate(LocalDate lotteryDate, Instant now) {
        if (regenerateInterval.isZero() || regenerateInterval.isNegative()) {
            return true;
        }
        Instant lastGeneratedAt = predictionMapper.selectLatestGeneratedAtByLotteryDate(lotteryDate);
        return lastGeneratedAt == null || !lastGeneratedAt.plus(regenerateInterval).isAfter(now);
    }

    private void recordFailure(Long predictionId, String error) {
        try {
            predictionMapper.recordPublishFailure(predictionId, error);
        } catch (RuntimeException exception) {
            // 落库失败不能让整轮抛错：原始失败原因比「失败痕迹没写进去」重要得多
            log.warn(
                    "event=prediction_auto_publish_attempt_not_recorded predictionId={} exceptionType={}",
                    predictionId,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private static String truncate(String message) {
        String text = StringUtils.hasText(message) ? message.trim() : "publish failed";
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    private static PredictionAutoPublishVo skipped(
            LocalDate lotteryDate,
            long pendingMatchCount,
            boolean generationPerformed,
            int generatedCount,
            int insertedCount,
            String reason
    ) {
        return new PredictionAutoPublishVo(
                lotteryDate,
                pendingMatchCount,
                generationPerformed,
                generatedCount,
                insertedCount,
                0,
                0,
                0,
                0,
                reason,
                Map.of()
        );
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
