package com.jingcaicompass.admin.vo;

import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;

/** 不包含原始响应内容的官方赛果事实版本。 */
public record AdminResultFactVo(
        Long factId,
        Integer factVersion,
        Integer supersedesFactVersion,
        MatchResultFactStatusEnum factStatus,
        MatchStatusEnum matchStatus,
        Integer homeScore,
        Integer awayScore,
        Instant providerUpdatedAt,
        boolean current,
        Instant createdAt
) {
}
