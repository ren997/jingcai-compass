package com.jingcaicompass.backtest.dto;

/**
 * 逐场明细查询。
 *
 * @param marketScope  只看某个市场有方向的样本；空表示不筛
 * @param onlyPicked   true 表示只返回至少一个市场有方向的样本（跳过原因不为空的一律排除）
 * @param skipReason   精确匹配某个跳过原因；空表示不筛
 */
public record BacktestCaseQueryDto(
        String groupKey,
        String marketScope,
        String skipReason,
        Boolean onlyPicked,
        Integer page,
        Integer pageSize
) {

    public BacktestCaseQueryDto {
        onlyPicked = Boolean.TRUE.equals(onlyPicked);
    }
}
