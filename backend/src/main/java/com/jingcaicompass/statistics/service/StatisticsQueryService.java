package com.jingcaicompass.statistics.service;

import com.jingcaicompass.statistics.dto.StatisticsSummaryQueryDto;
import com.jingcaicompass.statistics.vo.StatisticsSummaryVo;

/** 面向公开端的历史表现统计查询。 */
public interface StatisticsQueryService {

    /**
     * 返回请求范围、近 7 天和近 30 天的统一指标及请求范围分组。
     *
     * @param query 可为空的筛选条件
     */
    StatisticsSummaryVo summary(StatisticsSummaryQueryDto query);
}
