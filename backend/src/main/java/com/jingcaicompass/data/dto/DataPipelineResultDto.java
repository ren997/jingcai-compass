package com.jingcaicompass.data.dto;

import com.jingcaicompass.data.enums.DataPipelineStatusEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单源同步编排的单次业务日报告。
 *
 * @param businessDate 竞彩业务日
 * @param status 流水线整体状态
 * @param sportterySyncRunId 赛程同步运行 ID
 * @param sportteryStatus 赛程同步状态
 * @param asianOddsSyncRunId 亚盘同步运行 ID
 * @param asianOddsStatus 亚盘同步状态
 * @param sportteryMatchUpsertCount 比赛写入或更新数
 * @param sportterySnapshotInsertCount 体彩快照新增数
 * @param asianOddsSnapshotInsertCount 亚盘快照新增数
 * @param skippedUnmapped 因赛程里找不到对应场次而跳过数
 * @param skippedLive 因已开赛（滚球）而跳过数
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
