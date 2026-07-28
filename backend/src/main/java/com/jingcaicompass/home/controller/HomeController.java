package com.jingcaicompass.home.controller;

import com.jingcaicompass.home.service.HomeSummaryQueryService;
import com.jingcaicompass.home.vo.HomeSummaryVo;
import com.jingcaicompass.system.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开首页汇总接口。 */
@RestController
@RequestMapping("/api/public/home")
public class HomeController {

    private final HomeSummaryQueryService homeSummaryQueryService;

    public HomeController(HomeSummaryQueryService homeSummaryQueryService) {
        this.homeSummaryQueryService = homeSummaryQueryService;
    }

    @GetMapping("/summary")
    public ApiResponse<HomeSummaryVo> summary() {
        return ApiResponse.success(homeSummaryQueryService.summary());
    }
}
