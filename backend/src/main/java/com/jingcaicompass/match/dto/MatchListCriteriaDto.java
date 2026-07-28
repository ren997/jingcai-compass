package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MatchListSortEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.LocalDate;
import java.util.Set;

/** 已归一化的公开比赛数据库查询条件。 */
public record MatchListCriteriaDto(
        LocalDate lotteryDate,
        Long leagueId,
        Set<MatchStatusEnum> matchStatuses,
        MatchListSortEnum sort,
        long pageSize,
        long offset
) {
}
