package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MatchMapOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import java.math.BigDecimal;
import java.util.List;

/**
 * 双源比赛自动映射出参。
 *
 * @param mappingId 映射行 ID；无候选时为 null
 * @param matchId 内部比赛 ID；无候选时为 null
 * @param outcome 解析结果
 * @param mappingStatus 落库状态；无候选时为 null
 * @param confidence 置信度
 * @param explanation 可读解释
 * @param method 映射方法码
 * @param candidates 候选摘要
 */
public record MatchMapResultDto(
        Long mappingId,
        Long matchId,
        MatchMapOutcomeEnum outcome,
        MappingStatusEnum mappingStatus,
        BigDecimal confidence,
        String explanation,
        String method,
        List<MatchMapCandidateDto> candidates
) {
}
