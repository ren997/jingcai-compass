package com.jingcaicompass.match.dto;

import com.jingcaicompass.data.dto.ProviderSyncOutcome;

/**
 * 体彩比赛池同步结果。
 */
public record SportteryPoolSyncResultDto(
        ProviderSyncOutcome outcome,
        /** 成功落库/更新的比赛数。 */
        int matchUpsertCount,
        /** 新追加的快照数。 */
        int snapshotInsertCount
) {
}
