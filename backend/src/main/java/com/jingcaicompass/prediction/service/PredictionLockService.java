package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionLockResultDto;

/** 到期预测批量锁定入口。 */
public interface PredictionLockService {

    /**
     * 使用 PostgreSQL 当前时间锁定一批到期预测。
     *
     * @param batchSize 本批最多处理数量
     * @return 锁定、失败和耗时统计
     */
    PredictionLockResultDto lockDuePredictions(int batchSize);
}
