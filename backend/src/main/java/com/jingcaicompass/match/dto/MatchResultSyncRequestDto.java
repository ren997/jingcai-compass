package com.jingcaicompass.match.dto;

import java.time.LocalDate;

/** 体彩赛果同步的竞彩业务日范围。 */
public record MatchResultSyncRequestDto(
        /** 起始竞彩业务日，含。 */
        LocalDate startDate,
        /** 结束竞彩业务日，含。 */
        LocalDate endDate
) {
}
