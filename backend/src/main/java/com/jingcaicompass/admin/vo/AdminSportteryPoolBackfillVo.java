package com.jingcaicompass.admin.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 赛程池回填的逐日摘要，不含原始载荷。
 *
 * @param dayCount            请求覆盖的天数
 * @param successDays         成功完成的天数
 * @param failedDays          抛错的天数（其余天照常执行，不因一天失败整段回滚）
 * @param matchUpsertCount    累计落库/更新的比赛数
 * @param snapshotInsertCount 累计追加的比赛池快照数
 * @param quotaCost           累计上游调用单位
 * @param days                逐日明细，失败日单列原因
 */
public record AdminSportteryPoolBackfillVo(
        LocalDate startDate,
        LocalDate endDate,
        int dayCount,
        int successDays,
        int failedDays,
        int matchUpsertCount,
        int snapshotInsertCount,
        int quotaCost,
        List<DayResult> days,
        String errorSummary
) {

    /** 单日结果：缺哪天必须让操作者看得见，不能只回一个总数。 */
    public record DayResult(
            LocalDate date,
            boolean success,
            int matchUpsertCount,
            int snapshotInsertCount,
            int quotaCost,
            String errorSummary
    ) {
    }
}
