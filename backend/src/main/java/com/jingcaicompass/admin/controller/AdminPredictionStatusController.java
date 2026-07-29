package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminPredictionLockListQueryDto;
import com.jingcaicompass.admin.dto.AdminPredictionStatusDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSettlementStatusListQueryDto;
import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.vo.AdminPredictionStatusDetailVo;
import com.jingcaicompass.admin.vo.AdminPredictionStatusPageVo;
import com.jingcaicompass.system.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受管理员 JWT 保护的预测锁定与结算状态只读接口。 */
@RestController
@RequestMapping("/api/admin/prediction-status")
public class AdminPredictionStatusController {

    private final AdminPredictionStatusQueryService queryService;

    public AdminPredictionStatusController(AdminPredictionStatusQueryService queryService) {
        this.queryService = queryService;
    }

    /** 分页读取预测锁定状态。 */
    @PostMapping("/locks/list")
    public ApiResponse<AdminPredictionStatusPageVo> locks(
            @Valid @RequestBody(required = false) AdminPredictionLockListQueryDto query
    ) {
        return ApiResponse.success(queryService.locks(query));
    }

    /** 分页读取已锁定预测的结算运营状态。 */
    @PostMapping("/settlements/list")
    public ApiResponse<AdminPredictionStatusPageVo> settlements(
            @Valid @RequestBody(required = false) AdminSettlementStatusListQueryDto query
    ) {
        return ApiResponse.success(queryService.settlements(query));
    }

    /** 读取单条预测的当前状态和版本链。 */
    @PostMapping("/detail")
    public ApiResponse<AdminPredictionStatusDetailVo> detail(
            @Valid @RequestBody AdminPredictionStatusDetailQueryDto query
    ) {
        return ApiResponse.success(queryService.detail(query));
    }
}
