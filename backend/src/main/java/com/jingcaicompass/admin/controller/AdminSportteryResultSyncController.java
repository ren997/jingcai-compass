package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminSportteryResultSyncDto;
import com.jingcaicompass.admin.service.AdminSportteryResultSyncService;
import com.jingcaicompass.admin.vo.AdminSportteryResultSyncVo;
import com.jingcaicompass.system.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受管理员 JWT 保护的体彩赛果手动同步入口。 */
@RestController
@RequestMapping("/api/admin/provider/sporttery/results")
public class AdminSportteryResultSyncController {

    private final AdminSportteryResultSyncService adminSportteryResultSyncService;

    public AdminSportteryResultSyncController(AdminSportteryResultSyncService adminSportteryResultSyncService) {
        this.adminSportteryResultSyncService = adminSportteryResultSyncService;
    }

    /** 手动拉取赛果事实；不会触发预测结算。 */
    @PostMapping("/sync")
    public ApiResponse<AdminSportteryResultSyncVo> sync(
            @RequestBody(required = false) AdminSportteryResultSyncDto request
    ) {
        return ApiResponse.success(adminSportteryResultSyncService.sync(request));
    }
}
