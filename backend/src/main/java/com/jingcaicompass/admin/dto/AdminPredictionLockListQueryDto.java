package com.jingcaicompass.admin.dto;

import com.jingcaicompass.admin.enums.AdminPredictionLockDiagnosticEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** 后台预测锁定状态的分页筛选条件。 */
public record AdminPredictionLockListQueryDto(
        LocalDate lotteryDate,
        @Size(max = 64) String modelVersion,
        List<PredictionStatusEnum> predictionStatuses,
        List<AdminPredictionLockDiagnosticEnum> lockDiagnostics,
        Integer pageNo,
        Integer pageSize
) {
}
