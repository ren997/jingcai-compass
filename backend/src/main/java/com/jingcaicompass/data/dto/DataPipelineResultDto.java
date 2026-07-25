package com.jingcaicompass.data.dto;

import com.jingcaicompass.data.enums.DataPipelineStatusEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 双源同步编排的单次业务日报告。
 *
 * @param businessDate 竞彩业务日
 * @param status 流水线整体状态
 * @param sportterySyncRunId 体彩同步运行 ID
 * @param sportteryStatus 体彩同步状态
 * @param asianOddsSyncRunId 亚盘同步运行 ID
 * @param asianOddsStatus 亚盘同步状态
 * @param sportteryMatchUpsertCount 体彩比赛写入或更新数
 * @param sportterySnapshotInsertCount 体彩快照新增数
 * @param normalization 标准化回填报告
 * @param confirmedMappingCount 已确认比赛映射数
 * @param pendingMappingCount 待复核比赛映射数
 * @param validOddsMatchCount 已确认且具有有效盘口的比赛数
 * @param asianOddsSnapshotInsertCount 亚盘快照新增数
 * @param skippedUnmapped 因比赛未确认映射而跳过数
 * @param skippedLive 因滚球状态而跳过数
 * @param skippedIncomplete 因盘口字段不完整而跳过数
 * @param quotaBlocked 是否被 Provider 额度门禁阻止
 * @param coveredMatchCount 当日已有有效亚盘快照的比赛数
 * @param coverageRate 当日比赛盘口覆盖率
 * @param errorMessage 阶段失败摘要
 */
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
