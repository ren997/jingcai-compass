package com.jingcaicompass.odds.dto;

import java.time.OffsetDateTime;
import java.util.List;

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
        String bookmakerCode,
        /** 已由当天体彩联赛映射出的供应商 sport key；为空时不请求真实供应商。 */
        List<String> sportKeys
) {

    public AsianOddsQueryDto(
            String leagueId,
            OffsetDateTime kickoffFrom,
            OffsetDateTime kickoffTo,
            String bookmakerCode
    ) {
        this(leagueId, kickoffFrom, kickoffTo, bookmakerCode, List.of());
    }

    public AsianOddsQueryDto {
        sportKeys = sportKeys == null ? List.of() : List.copyOf(sportKeys);
    }
}
