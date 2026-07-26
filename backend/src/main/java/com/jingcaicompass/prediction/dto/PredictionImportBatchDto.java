package com.jingcaicompass.prediction.dto;

import java.util.List;

/**
 * 已解析并携带原始文件哈希的预测导入批次。
 *
 * @param generationBatchId 模型侧生成批次标识
 * @param generationBatchHash 原始 UTF-8 文件字节 SHA-256
 * @param predictions 当前批次预测列表
 */
public record PredictionImportBatchDto(
        String generationBatchId,
        String generationBatchHash,
        List<PredictionImportDto> predictions
) {

    public PredictionImportBatchDto {
        predictions = predictions == null ? null : List.copyOf(predictions);
    }
}
