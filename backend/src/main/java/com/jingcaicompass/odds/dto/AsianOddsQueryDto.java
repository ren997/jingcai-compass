package com.jingcaicompass.odds.dto;

import java.time.OffsetDateTime;

/**
 * 亚盘赛前赔率查询条件。
 */
public record AsianOddsQueryDto(
        /** 供应商联赛 ID，可空表示不按联赛过滤。 */
        String leagueId,
        /** 开赛时间下界（含）。 */
        OffsetDateTime kickoffFrom,
        /** 开赛时间上界（含）。 */
        OffsetDateTime kickoffTo,
        /** 目标博彩公司编码，可空表示全部。 */
        String bookmakerCode
) {
}
