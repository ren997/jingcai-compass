package com.jingcaicompass.admin.vo;

import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.time.Instant;
import java.util.List;

/** 管理员锁定或结算列表共用的一条预测状态摘要。 */
public record AdminPredictionStatusItemVo(
        Long predictionId,
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
        AdminSettlementMarketVo hadSettlement,
        AdminSettlementMarketVo hhadSettlement,
        List<AdminStatusDiagnosticVo> settlementDiagnostics
) {
    public AdminPredictionStatusItemVo {
        lockDiagnostics = List.copyOf(lockDiagnostics);
        settlementDiagnostics = List.copyOf(settlementDiagnostics);
    }
}
