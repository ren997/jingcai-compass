package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MappingStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 以竞彩比赛为主体的映射复核列表项。
 *
 * @param match 竞彩比赛摘要
 * @param externalCandidates 该比赛可安全确认的外部比赛候选
 */
public record MappingReviewMatchListItemVo(
        MappingReviewDetailVo.MatchBriefVo match,
        List<ExternalCandidateVo> externalCandidates
) {

    /** 已由服务端候选规则限定的外部比赛；不可据此手工输入任意关联。 */
    public record ExternalCandidateVo(
            Long mappingId,
            String providerCode,
            String externalMatchId,
            String externalLeagueId,
            String externalHomeTeamName,
            String externalAwayTeamName,
            Instant externalKickoffTime,
            MappingStatusEnum mappingStatus,
            BigDecimal score,
            List<String> reasons,
            String mappingExplanation,
            Instant updatedAt
    ) {
    }
}
