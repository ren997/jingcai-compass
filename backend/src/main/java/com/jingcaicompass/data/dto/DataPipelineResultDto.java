package com.jingcaicompass.data.dto;

import com.jingcaicompass.data.enums.DataPipelineStatusEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 双源同步编排的单次业务日报告。 */
public record DataPipelineResultDto(
        LocalDate businessDate,
        DataPipelineStatusEnum status,
        Long sportterySyncRunId,
        SyncStatusEnum sportteryStatus,
        Long asianOddsSyncRunId,
        SyncStatusEnum asianOddsStatus,
        int sportteryMatchUpsertCount,
        int sportterySnapshotInsertCount,
        NormalizationBackfillResultDto normalization,
        int confirmedMappingCount,
        int pendingMappingCount,
        int validOddsMatchCount,
        int asianOddsSnapshotInsertCount,
        int skippedUnmapped,
        int skippedLive,
        int skippedIncomplete,
        boolean quotaBlocked,
        int coveredMatchCount,
        BigDecimal coverageRate,
        String errorMessage
) {
}
