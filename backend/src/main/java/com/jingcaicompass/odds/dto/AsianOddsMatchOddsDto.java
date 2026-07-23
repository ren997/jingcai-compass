package com.jingcaicompass.odds.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 亚盘供应商赛事及其盘口列表。
 */
public record AsianOddsMatchOddsDto(
        /** 供应商侧赛事 ID。 */
        String providerMatchId,
        /** 主队名称。 */
        String homeTeamName,
        /** 客队名称。 */
        String awayTeamName,
        /** 预计开赛时间。 */
        OffsetDateTime kickoffTime,
        /** 是否滚球盘。 */
        boolean live,
        /** 可用亚盘盘口。 */
        List<AsianOddsLineDto> lines
) {
}
