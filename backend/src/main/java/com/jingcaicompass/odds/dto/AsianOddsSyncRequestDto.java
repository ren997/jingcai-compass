package com.jingcaicompass.odds.dto;

import java.time.LocalDate;

/** 亚盘同步请求；businessDate 为空时用上海今日。 */
public record AsianOddsSyncRequestDto(
        /** 竞彩业务日（上海日历日）。 */
        LocalDate businessDate
) {
}
