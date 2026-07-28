package com.jingcaicompass.home.vo;

/** 上海当天比赛池及公开预测的去重场次数。 */
public record HomeTodayOverviewVo(
        long matchCount,
        long publishedPredictionMatchCount
) {
}
