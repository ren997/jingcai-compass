package com.jingcaicompass.odds.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 单条亚洲让球盘口赔率；大小球三字段可选，要么全空要么全有。
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
        /** 大小球盘口线，可空。 */
        BigDecimal totalLine,
        /** 大球赔率，可空。 */
        BigDecimal overOdds,
        /** 小球赔率，可空。 */
        BigDecimal underOdds,
        /** 供应商侧更新时间。 */
        OffsetDateTime providerUpdatedAt
) {
}
