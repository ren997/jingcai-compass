package com.jingcaicompass.admin.vo;

import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;

/** 不包含原始响应 JSON 的赛果事实版本；后台可追溯人工补录说明。 */
public record AdminResultFactVo(
        Long factId,
        Integer factVersion,
        Integer supersedesFactVersion,
        MatchResultFactStatusEnum factStatus,
        MatchStatusEnum matchStatus,
        Integer homeScore,
        Integer awayScore,
        Instant providerUpdatedAt,
        MatchResultFactSourceEnum resultSource,
        String sourceNote,
        String entryReason,
        String enteredBy,
        boolean current,
        Instant createdAt
) {
    public AdminResultFactVo(
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
        this(factId, factVersion, supersedesFactVersion, factStatus, matchStatus, homeScore, awayScore,
                providerUpdatedAt, MatchResultFactSourceEnum.OFFICIAL, null, null, null, current, createdAt);
    }
}
