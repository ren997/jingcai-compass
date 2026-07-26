package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminLoginDto;
import com.jingcaicompass.admin.vo.AdminLoginVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/** 无 DataSource 的快速测试上下文中保持认证接口安全失败。 */
@Service
@ConditionalOnMissingBean(DataSource.class)
public class NoOpAdminAuthService implements AdminAuthService {

    @Override
    public AdminLoginVo login(AdminLoginDto request) {
        throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Override
    public void logout(Long adminId, String authenticatedUsername) {
        throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
    }
}
