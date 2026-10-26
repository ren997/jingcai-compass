package com.jingcaicompass.backtest.dto;

import java.time.LocalDate;

/**
 * 回测列表查询。
 *
 * @param runStatus 可选的状态过滤；空表示全部
 */
public record BacktestRunListQueryDto(
        String runStatus,
        LocalDate dateFrom,
        LocalDate dateTo,
        Integer page,
        Integer pageSize
) {
}
