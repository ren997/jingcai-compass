package com.jingcaicompass.statistics.vo;

/** 请求窗口内按模型版本汇总的统计。 */
public record ModelVersionStatisticsVo(
        String modelVersion,
        StatisticsMetricsVo metrics
) {
}
