package com.jingcaicompass.admin.mapper;

import com.jingcaicompass.admin.enums.AdminPredictionLockDiagnosticEnum;
import com.jingcaicompass.admin.enums.AdminSettlementDiagnosticEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 管理员状态查询在 Mapper 中共用的已规范化条件。 */
public record AdminPredictionStatusCriteria(
        LocalDate lotteryDate,
        String modelVersion,
        List<PredictionStatusEnum> predictionStatuses,
        List<AdminPredictionLockDiagnosticEnum> lockDiagnostics,
        List<AdminSettlementDiagnosticEnum> settlementDiagnostics,
        int pageSize,
        long offset,
        Instant referenceTime
) {
}
