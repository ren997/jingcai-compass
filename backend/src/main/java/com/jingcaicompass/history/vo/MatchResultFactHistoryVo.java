package com.jingcaicompass.history.vo;

import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;

/** 一条不可变官方赛果事实版本；不返回原始响应内容。 */
public record MatchResultFactHistoryVo(
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
