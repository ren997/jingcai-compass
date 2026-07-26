package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminLoginDto;
import com.jingcaicompass.admin.service.AdminAuthService;
import com.jingcaicompass.admin.vo.AdminLoginVo;
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

/** 管理员登录与即时撤销退出接口。 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final ObjectProvider<AdminAuthService> adminAuthServiceProvider;

    public AdminAuthController(ObjectProvider<AdminAuthService> adminAuthServiceProvider) {
        this.adminAuthServiceProvider = adminAuthServiceProvider;
    }

    /** 校验账号密码并返回短期 Bearer Token。 */
    @PostMapping("/login")
    public ApiResponse<AdminLoginVo> login(@Valid @RequestBody AdminLoginDto request) {
        return ApiResponse.success(requireAuthService(
                ErrorCode.AUTH_INVALID_CREDENTIALS
        ).login(request));
    }

    /** 递增账号 Token 版本，使该账号全部旧 JWT 立即失效。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        Long adminId;
        try {
            adminId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        requireAuthService(ErrorCode.AUTH_UNAUTHORIZED)
                .logout(adminId, jwt.getClaimAsString("username"));
        return ApiResponse.success(null);
    }

    private AdminAuthService requireAuthService(ErrorCode unavailableError) {
        AdminAuthService service = adminAuthServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(unavailableError);
        }
        return service;
    }
}
