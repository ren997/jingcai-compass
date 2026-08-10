package com.jingcaicompass.admin.vo;

import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;

/** 管理员人工赛果补录结果，不含原始证据 JSON。 */
public record AdminManualMatchResultVo(
        Long factId,
        Integer factVersion,
        String writeOutcome,
        MatchResultFactSourceEnum resultSource,
        MatchResultFactStatusEnum factStatus,
        MatchStatusEnum matchStatus,
        Integer homeScore,
        Integer awayScore,
        String sourceNote,
        String entryReason,
        String enteredBy,
        Instant enteredAt,
        boolean settlementTriggered,
        AdminManualResultSettlementVo settlement
) {
}
