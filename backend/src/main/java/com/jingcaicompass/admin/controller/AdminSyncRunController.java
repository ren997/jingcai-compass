package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminSyncRunDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunErrorQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunListQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunQuotaSummaryQueryDto;
import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.admin.vo.AdminSyncRunDetailVo;
import com.jingcaicompass.admin.vo.AdminSyncRunErrorVo;
import com.jingcaicompass.admin.vo.AdminSyncRunListItemVo;
import com.jingcaicompass.admin.vo.AdminSyncRunQuotaSummaryVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受管理员 JWT 保护的同步运行、错误和额度观测接口。 */
@RestController
@RequestMapping("/api/admin/provider/sync-runs")
public class AdminSyncRunController {

    private final AdminSyncRunQueryService adminSyncRunQueryService;

    public AdminSyncRunController(AdminSyncRunQueryService adminSyncRunQueryService) {
        this.adminSyncRunQueryService = adminSyncRunQueryService;
    }

    /** 分页读取后台同步运行。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<AdminSyncRunListItemVo>> list(
            @RequestBody(required = false) AdminSyncRunListQueryDto query
    ) {
        return ApiResponse.success(adminSyncRunQueryService.list(query));
    }

    /** 读取单次同步运行及安全载荷片段。 */
    @PostMapping("/detail")
    public ApiResponse<AdminSyncRunDetailVo> detail(
            @Valid @RequestBody AdminSyncRunDetailQueryDto query
    ) {
        return ApiResponse.success(adminSyncRunQueryService.detail(query));
    }

    /** 分页读取失败和部分成功运行。 */
    @PostMapping("/errors/list")
    public ApiResponse<PageResult<AdminSyncRunErrorVo>> errors(
            @RequestBody(required = false) AdminSyncRunErrorQueryDto query
    ) {
        return ApiResponse.success(adminSyncRunQueryService.errors(query));
    }

    /** 汇总上海业务日的已消耗额度和预警阈值。 */
    @PostMapping("/quota/summary")
    public ApiResponse<AdminSyncRunQuotaSummaryVo> quotaSummary(
            @RequestBody(required = false) AdminSyncRunQuotaSummaryQueryDto query
    ) {
        return ApiResponse.success(adminSyncRunQueryService.quotaSummary(query));
    }
}
