package com.jingcaicompass.admin.dto;

import com.jingcaicompass.admin.enums.AdminSettlementDiagnosticEnum;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** 后台已锁定预测结算状态的分页筛选条件。 */
public record AdminSettlementStatusListQueryDto(
        LocalDate lotteryDate,
        @Size(max = 64) String modelVersion,
        List<AdminSettlementDiagnosticEnum> diagnostics,
        Integer pageNo,
        Integer pageSize
) {
}
