package com.jingcaicompass.prediction.vo;

import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.util.List;

/**
 * 批量发布结果。
 *
 * <p>逐条返回而不是只给汇总数：部分失败时运营需要知道具体哪几条没发出去。
 * 只回 successCount 会让人重跑整批，而已成功的条目会被幂等复用，等于白跑一遍。
 *
 * @param requestedCount 请求条数（已去重）
 * @param successCount 发布成功条数（含幂等复用）
 * @param failureCount 发布失败条数
 * @param results 逐条结果，顺序与执行顺序一致
 */
public record PredictionBatchPublishResultVo(
        int requestedCount,
        int successCount,
        int failureCount,
        List<Item> results
) {

    public PredictionBatchPublishResultVo {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * 单条发布结果。
     *
     * @param predictionId 预测 ID
     * @param success 是否成功
     * @param matchId 内部比赛 ID；失败或未查到记录时为空
     * @param predictionVersion 同比赛模型下的发布版本；失败时为空
     * @param predictionStatus 发布后状态；失败时为空
     * @param predictionHash 规范化内容哈希；失败时为空
     * @param reused 是否为已发布结果的幂等复用
     * @param errorMessage 失败原因；成功时为空
     */
    public record Item(
            Long predictionId,
            boolean success,
            Long matchId,
            Integer predictionVersion,
            PredictionStatusEnum predictionStatus,
            String predictionHash,
            boolean reused,
            String errorMessage
    ) {

        /** 由单条发布结果构造成功项。 */
        public static Item success(PredictionPublishResultVo result) {
            return new Item(
                    result.predictionId(),
                    true,
                    result.matchId(),
                    result.predictionVersion(),
                    result.predictionStatus(),
                    result.predictionHash(),
                    result.reused(),
                    null
            );
        }

        /** 由异常构造失败项；保留 matchId 便于按比赛分组排查。 */
        public static Item failure(Long predictionId, Long matchId, String errorMessage) {
            return new Item(predictionId, false, matchId, null, null, null, false, errorMessage);
        }
    }
}
