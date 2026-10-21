package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminOddsLadderQueryDto;
import com.jingcaicompass.admin.service.AdminOddsLadderQueryService;
import com.jingcaicompass.admin.vo.AdminOddsLadderVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台盘口阶梯只读接口。
 *
 * <p>独立于发布控制器：发布接口带副作用且路径语义是「动作」，把只读查询挂上去会让
 * 权限审计与限流规则混在同一个入口里。
 */
@RestController
@RequestMapping("/api/admin/predictions")
public class AdminOddsLadderController {

    private final ObjectProvider<AdminOddsLadderQueryService> queryServiceProvider;

    public AdminOddsLadderController(ObjectProvider<AdminOddsLadderQueryService> queryServiceProvider) {
        this.queryServiceProvider = queryServiceProvider;
    }

    /** 读取指定亚盘预测当时的盘口阶梯与冻结行。 */
    @PostMapping("/odds-ladder")
    public ApiResponse<AdminOddsLadderVo> oddsLadder(@Valid @RequestBody AdminOddsLadderQueryDto request) {
        AdminOddsLadderQueryService service = queryServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "odds ladder service is unavailable"
            );
        }
        return ApiResponse.success(service.query(request.predictionId()));
    }
}
