package com.jingcaicompass.history.vo;

import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/** 一条不可变赛果事实版本；不返回原始响应内容或人工证据细节。 */
public record MatchResultFactHistoryVo(
        Long factId,
        Integer factVersion,
        Integer supersedesFactVersion,
        MatchResultFactStatusEnum factStatus,
        MatchStatusEnum matchStatus,
        Integer homeScore,
        Integer awayScore,
        Instant providerUpdatedAt,
        MatchResultFactSourceEnum resultSource,
        @JsonIgnore String sourceNote,
        @JsonIgnore String entryReason,
        @JsonIgnore String enteredBy,
        boolean current,
        Instant createdAt
) {
    public MatchResultFactHistoryVo(
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
