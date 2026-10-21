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
        boolean hasPublishedPrediction,
        MatchListSortEnum sort,
        long pageSize,
        long offset
) {

    /** 兼容未使用已发布预测筛选的既有调用方。 */
    public MatchListCriteriaDto(
            LocalDate lotteryDate,
            Long leagueId,
            Set<MatchStatusEnum> matchStatuses,
            MatchListSortEnum sort,
            long pageSize,
            long offset
    ) {
        this(lotteryDate, leagueId, matchStatuses, false, sort, pageSize, offset);
    }
}
