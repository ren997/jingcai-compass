package com.jingcaicompass.odds.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 亚盘赛前赔率查询条件。
 *
 * <p>{@code sportKeys} 与 {@code matchExternalIds} 分别对应两种取数模型，二者互斥：
 * The Odds API 按联赛批量拉（用 {@code sportKeys}），NovaAPI 按 {@code matchId} 逐场拉
 * （用 {@code matchExternalIds}）。这不是冗余字段——单源改造期间两条链路必须能并存回退。</p>
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
        List<String> sportKeys,
        /** 供应商侧比赛 ID 列表；NovaAPI 逐场拉取时必填。 */
        List<String> matchExternalIds
) {

    public AsianOddsQueryDto(
            String leagueId,
            OffsetDateTime kickoffFrom,
            OffsetDateTime kickoffTo,
            String bookmakerCode,
            List<String> sportKeys
    ) {
        this(leagueId, kickoffFrom, kickoffTo, bookmakerCode, sportKeys, List.of());
    }

    public AsianOddsQueryDto(
            String leagueId,
            OffsetDateTime kickoffFrom,
            OffsetDateTime kickoffTo,
            String bookmakerCode
    ) {
        this(leagueId, kickoffFrom, kickoffTo, bookmakerCode, List.of(), List.of());
    }

    public AsianOddsQueryDto {
        sportKeys = sportKeys == null ? List.of() : List.copyOf(sportKeys);
        matchExternalIds = matchExternalIds == null ? List.of() : List.copyOf(matchExternalIds);
    }
}
