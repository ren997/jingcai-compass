package com.jingcaicompass.match.api.vo;

import com.jingcaicompass.match.domain.MatchStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record MatchSummaryVo(
        /** 数据源内稳定的比赛标识。 */
        String matchId,
        /** 中国体彩归属日期。 */
        LocalDate lotteryDate,
        /** 中国体彩风格的比赛编号。 */
        String lotteryMatchNo,
        /** 联赛中文名称。 */
        String leagueName,
        /** 主队名称。 */
        String homeTeamName,
        /** 客队名称。 */
        String awayTeamName,
        /** 预计开赛时间。 */
        OffsetDateTime kickoffTime,
        /** 体彩官方让球数，主队让球时为负数。 */
        Integer officialHandicap,
        /** 当前比赛状态。 */
        MatchStatus matchStatus,
        /** 当前记录的数据来源标识。 */
        String dataSource
) {
}
