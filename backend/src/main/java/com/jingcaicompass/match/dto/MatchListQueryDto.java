package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MatchListSortEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.LocalDate;
import java.util.Set;

/** 公开比赛分页列表筛选条件。 */
public record MatchListQueryDto(
        /** 竞彩业务日；为空时使用 Asia/Shanghai 当天。 */
        LocalDate lotteryDate,
        /** 标准联赛 ID。 */
        Long leagueId,
        /** 允许的比赛状态集合。 */
        Set<MatchStatusEnum> matchStatuses,
        /** 固定排序白名单。 */
        MatchListSortEnum sort,
        /** 页码，从 1 开始。 */
        Integer pageNo,
        /** 页大小，受服务端最大值限制。 */
        Integer pageSize
) {
}
