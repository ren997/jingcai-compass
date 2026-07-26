package com.jingcaicompass.prediction.dto;

import java.util.List;

/**
 * 预测批次导入结果。
 *
 * @param generationBatchId 生成批次标识
 * @param generationBatchHash 原始文件 SHA-256
 * @param totalCount 批次记录总数
 * @param insertedCount 新增记录数
 * @param reusedCount 幂等复用记录数
 * @param predictionIds 按输入顺序返回的预测 ID
 */
public record PredictionImportResultDto(
        String generationBatchId,
        String generationBatchHash,
        int totalCount,
        int insertedCount,
        int reusedCount,
        List<Long> predictionIds
) {

    public PredictionImportResultDto {
        predictionIds = List.copyOf(predictionIds);
    }
}
