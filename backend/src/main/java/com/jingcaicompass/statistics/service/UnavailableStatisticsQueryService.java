package com.jingcaicompass.statistics.service;

import com.jingcaicompass.statistics.dto.StatisticsSummaryQueryDto;
import com.jingcaicompass.statistics.vo.StatisticsSummaryVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

/** 无数据库运行配置下的公开统计服务占位，保留统一错误语义。 */
public class UnavailableStatisticsQueryService implements StatisticsQueryService {

    @Override
    public StatisticsSummaryVo summary(StatisticsSummaryQueryDto query) {
        throw new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "public statistics requires a database");
    }
}
