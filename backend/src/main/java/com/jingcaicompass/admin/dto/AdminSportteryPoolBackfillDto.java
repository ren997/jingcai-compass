package com.jingcaicompass.admin.dto;

import java.time.LocalDate;

/**
 * 管理员手动回填体彩赛程池的连续竞彩业务日范围。
 *
 * <p>存在的理由：定时任务只同步「当天」，而回测的候选场次完全来自本地 {@code matches}。
 * 缺了某个区间的赛程，回测会直接报「该区间没有可回测的场次」——那不是回测功能缺失，
 * 是数据没进来。补数据必须走生产同一条 {@code SportteryPoolSyncService}，
 * 不能手工 INSERT，否则队伍/联赛/外部比赛 ID 的解析规则会分叉。</p>
 */
public record AdminSportteryPoolBackfillDto(
        /** 起始竞彩业务日，含。 */
        LocalDate startDate,
        /** 结束竞彩业务日，含。 */
        LocalDate endDate
) {
}
