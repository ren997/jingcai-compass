package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.enums.MatchDataAvailabilityEnum;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 公开比赛详情中的最新体彩让球与 SP。 */
public record SportteryMarketVo(
        /** 体彩市场快照可用状态。 */
        MatchDataAvailabilityEnum availability,
        /** 关联原始载荷的 Provider 编码。 */
        String dataSource,
        OffsetDateTime capturedAt,
        OffsetDateTime providerUpdatedAt,
        BigDecimal officialHandicap,
        BigDecimal hadHomeSp,
        BigDecimal hadDrawSp,
        BigDecimal hadAwaySp,
        /** 让球胜平负主胜 SP。 */
        BigDecimal hhadHomeSp,
        /** 让球胜平负平局 SP。 */
        BigDecimal hhadDrawSp,
        /** 让球胜平负客胜 SP。 */
        BigDecimal hhadAwaySp,
        String sellStatus
) {
}
