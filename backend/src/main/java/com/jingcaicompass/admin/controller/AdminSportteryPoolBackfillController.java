package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminSportteryPoolBackfillDto;
import com.jingcaicompass.admin.service.AdminSportteryPoolBackfillService;
import com.jingcaicompass.admin.vo.AdminSportteryPoolBackfillVo;
import com.jingcaicompass.system.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受管理员 JWT 保护的体彩赛程池回填入口。
 *
 * <p>与 {@code /results/sync} 并列：那个补赛果，这个补赛程。回测候选场次只来自本地
 * {@code matches}，缺赛程时空有回测功能也跑不出样本。</p>
 */
@RestController
@RequestMapping("/api/admin/provider/sporttery/pool")
public class AdminSportteryPoolBackfillController {

    private final AdminSportteryPoolBackfillService adminSportteryPoolBackfillService;

    public AdminSportteryPoolBackfillController(
            AdminSportteryPoolBackfillService adminSportteryPoolBackfillService
    ) {
        this.adminSportteryPoolBackfillService = adminSportteryPoolBackfillService;
    }

    /** 按竞彩业务日区间逐日拉取赛程；单次最多 7 天。 */
    @PostMapping("/backfill")
    public ApiResponse<AdminSportteryPoolBackfillVo> backfill(
            @RequestBody AdminSportteryPoolBackfillDto request
    ) {
        return ApiResponse.success(adminSportteryPoolBackfillService.backfill(request));
    }
}
