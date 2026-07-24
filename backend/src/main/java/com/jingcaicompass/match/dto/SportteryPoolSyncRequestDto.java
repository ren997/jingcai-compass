package com.jingcaicompass.match.dto;

import java.time.LocalDate;

/**
 * 体彩比赛池同步请求。
 */
public record SportteryPoolSyncRequestDto(
        /** 竞彩业务日；空则使用上海当日。 */
        LocalDate businessDate
) {
}
