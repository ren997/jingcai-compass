package com.jingcaicompass.admin.vo;

import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.time.Instant;
import java.util.List;

/**
 * 管理员锁定或结算列表共用的一条预测状态摘要。
 *
 * <p>市场部分用列表而不是 {@code hadSettlement}/{@code hhadSettlement} 两个具名字段：
 * 具名字段把「HAD/HHAD 必然存在」写进了响应形状，亚盘预测只能退化成两个 PENDING，
 * 于是结算已落库也显示成「HAD 待结算」。市场集合由
 * {@code MarketTypeEnum.marketsFor(predictionType)} 决定。
 */
public record AdminPredictionStatusItemVo(
        Long predictionId,
        PredictionTypeEnum predictionType,
        String modelVersion,
        String featureVersion,
        Integer predictionVersion,
        PredictionStatusEnum predictionStatus,
        Instant publishTime,
        Instant lockTime,
        String predictionHash,
        AdminPredictionMatchVo match,
        List<AdminStatusDiagnosticVo> lockDiagnostics,
        AdminResultFactVo currentResultFact,
        List<AdminSettlementMarketVo> settlementMarkets,
        List<AdminStatusDiagnosticVo> settlementDiagnostics
) {
    public AdminPredictionStatusItemVo {
        settlementMarkets = List.copyOf(settlementMarkets);
        lockDiagnostics = List.copyOf(lockDiagnostics);
        settlementDiagnostics = List.copyOf(settlementDiagnostics);
    }
}
