package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MappingStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 映射复核列表项。
 *
 * @param mappingId 映射 ID
 * @param matchId 内部比赛 ID
 * @param providerCode 供应商编码
 * @param externalMatchId 外部比赛 ID
 * @param mappingStatus 状态
 * @param mappingConfidence 置信度
 * @param mappingMethod 方法
 * @param mappingExplanation 解释
 * @param candidateCount 候选数量
 * @param confirmedBy 确认人
 * @param updatedAt 更新时间
 */
public record MappingReviewListItemVo(
        Long mappingId,
        Long matchId,
        String providerCode,
        String externalMatchId,
        MappingStatusEnum mappingStatus,
        BigDecimal mappingConfidence,
        String mappingMethod,
        String mappingExplanation,
        int candidateCount,
        String confirmedBy,
        Instant updatedAt
) {
}
