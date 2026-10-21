package com.jingcaicompass.prediction.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量发布请求。
 *
 * <p>批量与单条发布走同一条业务路径，只是把「逐条点击」换成一次请求：前端此前用并发
 * 逐条调用，同场多条会互相争抢 `matches` 行锁。上限 200 是为了让一次请求的执行时间可控，
 * 与「一天有多少场」无关。
 *
 * @param predictionIds 待发布的 DRAFT 预测 ID，允许重复（服务端去重保序）
 */
public record PredictionBatchPublishDto(
        @NotEmpty
        @Size(max = 200)
        List<@NotNull @Positive Long> predictionIds
) {

    public PredictionBatchPublishDto {
        predictionIds = predictionIds == null ? null : List.copyOf(predictionIds);
    }
}
