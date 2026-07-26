package com.jingcaicompass.prediction.dto;

import java.util.List;

/**
 * 离线模型预测文件的批次结构。
 *
 * @param generationBatchId 模型侧生成批次标识
 * @param predictions 当前批次预测列表
 */
public record PredictionImportFileDto(
        String generationBatchId,
        List<PredictionImportDto> predictions
) {
}
