package com.jingcaicompass.match.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 比赛映射候选摘要。
 *
 * @param matchId 内部比赛 ID
 * @param score 综合得分
 * @param reasons 得分/拒绝原因码列表
 */
public record MatchMapCandidateDto(
        Long matchId,
        BigDecimal score,
        List<String> reasons
) {
}
