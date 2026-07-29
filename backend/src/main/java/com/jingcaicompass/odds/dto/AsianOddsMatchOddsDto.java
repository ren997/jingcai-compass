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
        List<AsianOddsLineDto> lines,
        /** 供应商侧联赛或 sport key，可空。 */
        String providerLeagueId,
        /** 单场响应的受控解析失败原因，可空。 */
        String parseError
) {

    public AsianOddsMatchOddsDto(
            String providerMatchId,
            String homeTeamName,
            String awayTeamName,
            OffsetDateTime kickoffTime,
            boolean live,
            List<AsianOddsLineDto> lines
    ) {
        this(providerMatchId, homeTeamName, awayTeamName, kickoffTime, live, lines, null, null);
    }
}
