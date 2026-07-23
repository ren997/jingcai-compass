package com.jingcaicompass.odds.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 单条亚洲让球盘口赔率。
 */
public record AsianOddsLineDto(
        /** 博彩公司编码。 */
        String bookmakerCode,
        /** 让球盘口线，主队让球为负。 */
        BigDecimal handicapLine,
        /** 主队赔率。 */
        BigDecimal homeOdds,
        /** 客队赔率。 */
        BigDecimal awayOdds,
        /** 供应商侧更新时间。 */
        OffsetDateTime providerUpdatedAt
) {
}
