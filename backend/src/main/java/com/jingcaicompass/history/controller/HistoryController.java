package com.jingcaicompass.history.controller;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.service.HistoryQueryService;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开预测、赛果事实和结算版本历史接口。 */
@RestController
@RequestMapping("/api/public/history")
public class HistoryController {

    private final HistoryQueryService historyQueryService;

    public HistoryController(HistoryQueryService historyQueryService) {
        this.historyQueryService = historyQueryService;
    }

    @PostMapping("/list")
    public ApiResponse<PageResult<HistoryListItemVo>> list(
            @RequestBody(required = false) HistoryListQueryDto query
    ) {
        return ApiResponse.success(historyQueryService.list(query));
    }
}
