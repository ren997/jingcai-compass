package com.jingcaicompass.admin.vo;

import com.jingcaicompass.admin.enums.AdminRoleEnum;
import java.time.Instant;

/**
 * 管理员登录结果。
 *
 * @param accessToken JWT 访问令牌
 * @param tokenType 固定为 Bearer
 * @param expiresAt 令牌过期时间
 * @param adminId 管理员账号 ID
 * @param username 管理员登录名
 * @param role 管理授权角色
 */
public record AdminLoginVo(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        Long adminId,
        String username,
        AdminRoleEnum role
) {
}
