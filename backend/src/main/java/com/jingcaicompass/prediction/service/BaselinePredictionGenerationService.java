package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import java.time.LocalDate;

/** 基于已持久化盘口生成并导入首版可解释预测。 */
public interface BaselinePredictionGenerationService {

    /**
     * 为指定竞彩业务日生成严格 JSON 批次并通过既有导入服务写入 DRAFT。
     *
     * @param lotteryDate 竞彩业务日（Asia/Shanghai）
     * @param operatorUsername 已认证管理员用户名，仅用于结构化运行日志
     * @return 生成、导入和跳过原因摘要
     */
    BaselinePredictionGenerationVo generateAndImport(LocalDate lotteryDate, String operatorUsername);
}
