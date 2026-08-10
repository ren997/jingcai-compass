package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;

/** 已由后台服务校验过的受控人工赛果事实输入。 */
public record ManualMatchResultFactDto(
        Long matchId,
        MatchResultFactStatusEnum factStatus,
        MatchStatusEnum matchStatus,
        Integer homeScore,
        Integer awayScore,
        String sourceNote,
        String entryReason,
        String enteredBy,
        Instant enteredAt
) {
}
