package com.jingcaicompass.prediction.vo;

import java.time.LocalDate;
import java.util.Map;

/**
 * 一轮自动发布的运行摘要。
 *
 * <p>刻意保留「生成」与「发布」两段计数而不是只给一个最终数：自动发布出问题时，
 * 需要立刻分清是「没生成」还是「生成了但发不出去」——这两类原因的排查方向完全不同。
 *
 * @param lotteryDate 目标竞彩业务日
 * @param pendingMatchCount 当天开赛时间未到、且尚未产生公开预测的比赛数
 * @param generationPerformed 本轮是否真的执行了生成
 * @param generatedCount 生成侧产出的预测数（未生成时为 0）
 * @param insertedCount 生成侧新插入的 DRAFT 数（未生成时为 0）
 * @param candidateCount 进入发布候选的 DRAFT 数
 * @param publishedCount 发布成功数（含幂等复用）
 * @param reusedCount 其中属于「本来就已发布」的幂等复用数
 * @param failedCount 发布失败数
 * @param skippedReason 未进入发布的短路原因；为空表示本轮执行过发布
 * @param failures 失败明细，键为预测 ID
 */
public record PredictionAutoPublishVo(
        LocalDate lotteryDate,
        long pendingMatchCount,
        boolean generationPerformed,
        int generatedCount,
        int insertedCount,
        int candidateCount,
        int publishedCount,
        int reusedCount,
        int failedCount,
        String skippedReason,
        Map<Long, String> failures
) {

    /** 当天所有可发布比赛都已有公开预测，连生成都不做。 */
    public static final String SKIP_NO_PENDING_MATCH = "NO_PENDING_MATCH";

    /** 还有未发布的比赛，但本轮没有任何可发布的 DRAFT（被发布门禁拒绝，或处于生成节流期）。 */
    public static final String SKIP_NO_CANDIDATE = "NO_CANDIDATE";

    public PredictionAutoPublishVo {
        failures = failures == null ? Map.of() : Map.copyOf(failures);
    }

    /** 有失败项的轮次记为 PARTIAL，用于任务指标。 */
    public String status() {
        if (skippedReason != null) {
            return "SKIPPED";
        }
        return failedCount == 0 ? "SUCCESS" : "PARTIAL";
    }
}
