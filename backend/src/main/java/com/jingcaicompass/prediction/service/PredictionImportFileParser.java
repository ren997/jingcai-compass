package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionImportBatchDto;

/** 离线模型预测文件解析契约。 */
public interface PredictionImportFileParser {

    /**
     * 严格解析 UTF-8 JSON，并基于原始字节计算批次哈希。
     *
     * @param fileContent 原始 UTF-8 JSON 文件字节
     * @return 已解析且携带原始文件哈希的批次
     */
    PredictionImportBatchDto parse(byte[] fileContent);
}
