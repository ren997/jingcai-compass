package com.jingcaicompass.match.dto;

import com.jingcaicompass.data.dto.ProviderSyncOutcome;

/** 一次体彩赛果同步的持久化结果摘要。 */
public record MatchResultSyncResultDto(
        /** 原始响应与同步运行结果。 */
        ProviderSyncOutcome outcome,
        /** 新追加的赛果事实数，含替代版本。 */
        int appendedFactCount,
        /** 其中替代既有当前事实的数量。 */
        int supersededFactCount,
        /** 与当前事实完全一致而跳过的数量。 */
        int unchangedFactCount
) {
}
