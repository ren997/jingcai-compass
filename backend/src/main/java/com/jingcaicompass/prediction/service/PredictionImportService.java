package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionImportResultDto;

/** 离线模型预测文件的整批导入入口。 */
public interface PredictionImportService {

    /**
     * 严格解析并整批导入预测；完全相同的批次重复提交时复用已有记录。
     *
     * @param fileContent 原始 UTF-8 JSON 文件字节
     * @return 批次新增或复用结果
     */
    PredictionImportResultDto importFile(byte[] fileContent);
}
