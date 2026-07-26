package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminLoginDto;
import com.jingcaicompass.admin.vo.AdminLoginVo;

/** 管理员登录和即时撤销退出入口。 */
public interface AdminAuthService {

    /** 校验账号密码并签发 30 分钟管理员访问令牌。 */
    AdminLoginVo login(AdminLoginDto request);

    /** 递增账号 tokenVersion，使当前账号全部旧令牌立即失效。 */
    void logout(Long adminId, String authenticatedUsername);
}
