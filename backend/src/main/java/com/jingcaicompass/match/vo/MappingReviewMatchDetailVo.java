package com.jingcaicompass.match.vo;

import java.util.List;

/** 竞彩比赛与其已持久化外部映射候选的人工复核详情。 */
public record MappingReviewMatchDetailVo(
        MappingReviewDetailVo.MatchBriefVo match,
        List<MappingReviewMatchListItemVo.ExternalCandidateVo> externalCandidates
) {
}
