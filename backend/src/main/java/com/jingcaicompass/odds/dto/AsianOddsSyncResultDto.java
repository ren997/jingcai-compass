package com.jingcaicompass.odds.dto;

import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import java.math.BigDecimal;

/**
 * 亚盘同步结果摘要。
 *
 * @param outcome 模板同步结果；额度阻断时为 null
 * @param quotaBlocked 是否因额度阈值跳过拉取
 * @param snapshotInsertCount 新插入快照行数
 * @param skippedUnmapped 未确认映射跳过场次数
 * @param skippedLive 滚球跳过场次数
 * @param skippedIncomplete 缺 AH 字段跳过 line 数
 * @param sportteryMatchCount 当日体彩比赛母集
 * @param coveredMatchCount 至少一条亚盘快照的比赛数
 * @param coverageRate 覆盖率 0～1
 * @param quotaCostUsed 本轮或已累计额度（阻断时为当日已用）
 */
public record AsianOddsSyncResultDto(
        ProviderSyncOutcome outcome,
        boolean quotaBlocked,
        int snapshotInsertCount,
        int skippedUnmapped,
        int skippedLive,
        int skippedIncomplete,
        int sportteryMatchCount,
        int coveredMatchCount,
        BigDecimal coverageRate,
        int quotaCostUsed
) {
}
