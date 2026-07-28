package com.jingcaicompass.home.vo;

import com.jingcaicompass.statistics.vo.StatisticsWindowVo;
import java.time.Instant;
import java.time.LocalDate;

/** 公开首页可由持久化比赛、预测、结算和快照事实重建的汇总。 */
public record HomeSummaryVo(
        LocalDate asOfDate,
        HomeTodayOverviewVo today,
        long pendingSettlementMatchCount,
        long historicalPublishedMatchCount,
        StatisticsWindowVo trailingSevenDays,
        StatisticsWindowVo trailingThirtyDays,
        HomeDataFreshnessVo dataFreshness,
        Instant latestPublishedSnapshotAt,
        Instant generatedAt
) {
}
