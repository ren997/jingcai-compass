package com.jingcaicompass.snapshot.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * 可公开复算的预测快照 manifest。
 *
 * @param schemaVersion manifest 结构版本
 * @param snapshotDate 竞彩业务日，格式为 yyyy-MM-dd
 * @param predictionCount 当前公开预测数量
 * @param predictions 按固定规则排序的当前公开预测
 */
@JsonPropertyOrder({"schemaVersion", "snapshotDate", "predictionCount", "predictions"})
public record PredictionSnapshotManifestDto(
        int schemaVersion,
        String snapshotDate,
        int predictionCount,
        List<PredictionSnapshotManifestItemDto> predictions
) {
}
