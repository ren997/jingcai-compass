package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** 使用数据库账号状态和 tokenVersion 验证每个管理员 JWT。 */
@Component
@ConditionalOnBean(DataSource.class)
public class AdminAccountTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_ADMIN_TOKEN = new OAuth2Error(
            "invalid_token",
            "administrator token is invalid or revoked",
            null
    );

    private final AdminAccountMapper adminAccountMapper;

    public AdminAccountTokenValidator(AdminAccountMapper adminAccountMapper) {
        this.adminAccountMapper = adminAccountMapper;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        // 1) 读取并验证必须由本系统签发的管理员身份声明
        Long adminId = parseAdminId(token.getSubject());
        String username = token.getClaimAsString("username");
        String role = token.getClaimAsString("role");
        Number tokenVersion = token.getClaim("tokenVersion");
        if (adminId == null || username == null || role == null || tokenVersion == null) {
            return failure();
        }

        // 2) 每次请求读取账号，使禁用和退出递增版本能够立即生效
        AdminAccount account = adminAccountMapper.selectById(adminId);
        if (account == null
                || account.getAccountStatus() != AdminAccountStatusEnum.ACTIVE
                || !username.equals(account.getUsername())
                || account.getRoleCode() == null
                || account.getTokenVersion() == null
                || tokenVersion.longValue() != account.getTokenVersion()) {
            return failure();
        }
        return OAuth2TokenValidatorResult.success();
    }

    private Long parseAdminId(String subject) {
        try {
            return subject == null ? null : Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(INVALID_ADMIN_TOKEN);
    }
}
