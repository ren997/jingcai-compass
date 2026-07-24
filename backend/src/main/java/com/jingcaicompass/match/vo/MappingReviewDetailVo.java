package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.dto.MatchMapCandidateDto;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 映射复核详情。
 *
 * @param mappingId 映射 ID
 * @param matchId 当前关联内部比赛 ID
 * @param providerCode 供应商编码
 * @param externalMatchId 外部比赛 ID
 * @param externalLeagueId 外部联赛 ID
 * @param externalHomeTeamId 外部主队 ID
 * @param externalAwayTeamId 外部客队 ID
 * @param mappingStatus 状态
 * @param mappingConfidence 置信度
 * @param mappingMethod 方法
 * @param mappingExplanation 解释
 * @param candidates 候选列表
 * @param confirmedBy 确认人
 * @param match 关联内部比赛摘要，可空
 * @param updatedAt 更新时间
 */
public record MappingReviewDetailVo(
        Long mappingId,
        Long matchId,
        String providerCode,
        String externalMatchId,
        String externalLeagueId,
        String externalHomeTeamId,
        String externalAwayTeamId,
        MappingStatusEnum mappingStatus,
        BigDecimal mappingConfidence,
        String mappingMethod,
        String mappingExplanation,
        List<MatchMapCandidateDto> candidates,
        String confirmedBy,
        MatchBriefVo match,
        Instant updatedAt
) {

    /**
     * 内部比赛简要信息。
     *
     * @param matchId 比赛 ID
     * @param lotteryMatchNo 体彩编号
     * @param lotteryDate 竞彩日期
     * @param leagueName 联赛名
     * @param homeTeamName 主队名
     * @param awayTeamName 客队名
     * @param kickoffTime 开赛时间
     */
    public record MatchBriefVo(
            Long matchId,
            String lotteryMatchNo,
            java.time.LocalDate lotteryDate,
            String leagueName,
            String homeTeamName,
            String awayTeamName,
            Instant kickoffTime
    ) {
    }
}
