package com.jingcaicompass.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员登录请求。
 *
 * @param username 管理员登录名，服务端统一转为小写
 * @param password 原始密码，仅用于本次认证
 */
public record AdminLoginDto(
        @NotBlank
        @Size(min = 3, max = 64)
        String username,
        @NotBlank
        @Size(max = 128)
        String password
) {
}
