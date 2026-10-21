package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionBatchPublishDto;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.PredictionBatchPublishResultVo;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/**
 * 逐条执行一次批量发布请求。
 *
 * <p>职责边界：只负责去重、排序与逐条调用，不复用单条发布的任何业务判断——
 * 发布规则（状态机、哈希、审计、幂等）全部留在 {@link PredictionPublishService} 里，
 * 否则两条路径会各自演化出不同的规则。
 */
public class PredictionBatchPublishService {

    private static final int MAX_ERROR_LENGTH = 200;

    private final PredictionMapper predictionMapper;
    private final PredictionPublishService publishService;

    public PredictionBatchPublishService(
            PredictionMapper predictionMapper,
            PredictionPublishService publishService
    ) {
        this.predictionMapper = predictionMapper;
        this.publishService = publishService;
    }

    /**
     * 逐条发布；单条失败不中断整批。
     *
     * <p>一次请求里同一场比赛可能有多条预测（不同模型/版本），它们都要更新 `matches` 行，
     * 因此执行顺序按 `(matchId, predictionId)` 升序固定下来，避免并发批次以相反顺序拿锁而死锁。
     * 逐条独立事务是关键：整批一个事务会让一条坏数据把已经发出去的条目一起回滚。
     *
     * @param request 待发布 ID 列表
     * @param operatorUsername 已认证管理员用户名
     * @return 汇总数与逐条结果
     */
    public PredictionBatchPublishResultVo publish(
            PredictionBatchPublishDto request,
            String operatorUsername
    ) {
        // 1) 去重并保持请求顺序：重复 ID 会被幂等复用，但仍会占一次执行，没有意义
        List<Long> requestedIds = new ArrayList<>(
                new LinkedHashSet<>(Objects.requireNonNull(request, "request must not be null").predictionIds())
        );

        // 2) 取 matchId 用于固定执行顺序；查不到的 ID 交给发布服务报错，这里不预先过滤
        Map<Long, Long> matchIdsByPredictionId = requestedIds.isEmpty()
                ? Map.of()
                : predictionMapper.selectBatchIds(requestedIds).stream()
                        .collect(Collectors.toMap(
                                Prediction::getId,
                                Prediction::getMatchId,
                                (left, right) -> left
                        ));
        List<Long> orderedIds = new ArrayList<>(requestedIds);
        orderedIds.sort(Comparator
                .comparing(
                        (Long predictionId) -> matchIdsByPredictionId.get(predictionId),
                        Comparator.nullsLast(Comparator.naturalOrder())
                )
                .thenComparing(Comparator.<Long>naturalOrder()));

        // 3) 逐条发布，失败只记录不抛出
        List<PredictionBatchPublishResultVo.Item> results = new ArrayList<>(orderedIds.size());
        for (Long predictionId : orderedIds) {
            Long matchId = matchIdsByPredictionId.get(predictionId);
            try {
                PredictionPublishResultVo published = publishService.publish(
                        new PredictionPublishDto(predictionId),
                        operatorUsername
                );
                results.add(PredictionBatchPublishResultVo.Item.success(published));
            } catch (RuntimeException exception) {
                results.add(PredictionBatchPublishResultVo.Item.failure(
                        predictionId,
                        matchId,
                        describe(exception)
                ));
            }
        }

        int successCount = (int) results.stream().filter(PredictionBatchPublishResultVo.Item::success).count();
        return new PredictionBatchPublishResultVo(
                orderedIds.size(),
                successCount,
                orderedIds.size() - successCount,
                results
        );
    }

    private String describe(RuntimeException exception) {
        String message = exception.getMessage();
        String text = StringUtils.hasText(message) ? message.trim() : exception.getClass().getSimpleName();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
