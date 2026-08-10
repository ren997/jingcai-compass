package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminManualMatchResultDto;
import com.jingcaicompass.admin.service.AdminManualMatchResultService;
import com.jingcaicompass.admin.vo.AdminManualMatchResultVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员 JWT 保护的人工赛果补录入口。 */
@RestController
@RequestMapping("/api/admin/manual-results")
public class AdminManualMatchResultController {

    private final ObjectProvider<AdminManualMatchResultService> manualMatchResultServiceProvider;

    public AdminManualMatchResultController(
            ObjectProvider<AdminManualMatchResultService> manualMatchResultServiceProvider
    ) {
        this.manualMatchResultServiceProvider = manualMatchResultServiceProvider;
    }

    /** 追加人工赛果事实并仅联动目标比赛的自动结算与重算。 */
    @PostMapping
    public ApiResponse<AdminManualMatchResultVo> record(
            @Valid @RequestBody AdminManualMatchResultDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(requireManualMatchResultService().record(request, requireUsername(jwt)));
    }

    private String requireUsername(Jwt jwt) {
        if (jwt == null || !org.springframework.util.StringUtils.hasText(jwt.getClaimAsString("username"))) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        return jwt.getClaimAsString("username");
    }

    private AdminManualMatchResultService requireManualMatchResultService() {
        AdminManualMatchResultService service = manualMatchResultServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return service;
    }
}
