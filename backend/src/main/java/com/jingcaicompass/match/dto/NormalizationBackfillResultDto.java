package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.NormalizationPendingReasonEnum;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 按业务日执行的比赛标准化回填报告。 */
public record NormalizationBackfillResultDto(
        LocalDate businessDate,
        int totalMatchCount,
        int normalizedMatchCount,
        int pendingMatchCount,
        int failureCount,
        int updatedMatchCount,
        Map<NormalizationPendingReasonEnum, Integer> pendingReasonCounts,
        List<NormalizationFailureDto> failures
) {

    public static NormalizationBackfillResultDto empty(LocalDate businessDate) {
        return new NormalizationBackfillResultDto(
                businessDate,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                List.of()
        );
    }
}
