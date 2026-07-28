package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MappingStatusEnum;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 公开详情中的供应商比赛映射状态与解释。 */
public record MatchSourceMappingVo(
        /** 来源 Provider 编码。 */
        String providerCode,
        String externalMatchId,
        MappingStatusEnum mappingStatus,
        BigDecimal mappingConfidence,
        String mappingMethod,
        /** 可读映射解释，不含原始载荷。 */
        String mappingExplanation,
        OffsetDateTime mappingUpdatedAt
) {
}
