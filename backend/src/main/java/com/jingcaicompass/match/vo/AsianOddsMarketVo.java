package com.jingcaicompass.match.vo;

import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 公开比赛详情中的单条当前亚盘线。 */
public record AsianOddsMarketVo(
        /** 亚盘 Provider 编码。 */
        String providerCode,
        /** 博彩公司或盘口来源编码。 */
        String bookmakerCode,
        /** 亚洲让球线，不等同体彩官方让球。 */
        BigDecimal handicapLine,
        BigDecimal homeOdds,
        BigDecimal awayOdds,
        /** 大小球线；三个大小球字段要么完整要么同时为空。 */
        BigDecimal totalLine,
        BigDecimal overOdds,
        BigDecimal underOdds,
        OddsSnapshotTypeEnum snapshotType,
        OffsetDateTime capturedAt,
        OffsetDateTime providerUpdatedAt
) {
}
