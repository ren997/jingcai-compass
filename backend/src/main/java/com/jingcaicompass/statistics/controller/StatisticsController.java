package com.jingcaicompass.statistics.controller;

import com.jingcaicompass.statistics.dto.StatisticsSummaryQueryDto;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.statistics.vo.StatisticsSummaryVo;
import com.jingcaicompass.system.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开历史表现统计接口。 */
@RestController
@RequestMapping("/api/public/statistics")
public class StatisticsController {

    private final StatisticsQueryService statisticsQueryService;

    public StatisticsController(StatisticsQueryService statisticsQueryService) {
        this.statisticsQueryService = statisticsQueryService;
    }

    @PostMapping("/summary")
    public ApiResponse<StatisticsSummaryVo> summary(
            @RequestBody(required = false) StatisticsSummaryQueryDto query
    ) {
        return ApiResponse.success(statisticsQueryService.summary(query));
    }
}
