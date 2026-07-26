package com.jingcaicompass.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.admin.dto.AdminLoginDto;
import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.enums.AdminLoginFailureReasonEnum;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.admin.vo.AdminLoginVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 校验管理员账号、提交失败状态，并为成功登录签发可撤销 JWT。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminAccountMapper adminAccountMapper;
    private final AdminAccountCredentialValidator credentialValidator;
    private final AdminLoginAttemptWriter loginAttemptWriter;
    private final AdminJwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthServiceImpl(
            AdminAccountMapper adminAccountMapper,
            AdminAccountCredentialValidator credentialValidator,
            AdminLoginAttemptWriter loginAttemptWriter,
            AdminJwtService jwtService,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.adminAccountMapper = adminAccountMapper;
        this.credentialValidator = credentialValidator;
        this.loginAttemptWriter = loginAttemptWriter;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("administrator-dummy-password");
    }

    @Override
    public AdminLoginVo login(AdminLoginDto request) {
        Objects.requireNonNull(request, "request must not be null");
        String username;
        try {
            username = credentialValidator.normalizeUsername(request.username());
        } catch (IllegalArgumentException exception) {
            rejectUnknown(String.valueOf(request.username()), request.password());
            throw invalidCredentials();
        }

        // 1) 未知用户名仍执行一次 BCrypt matches，降低账号枚举的时序差异
        AdminAccount account = adminAccountMapper.selectOne(new LambdaQueryWrapper<AdminAccount>()
                .eq(AdminAccount::getUsername, username));
        if (account == null) {
            rejectUnknown(username, request.password());
            throw invalidCredentials();
        }
        boolean passwordMatches = passwordEncoder.matches(
                Objects.toString(request.password(), ""),
                account.getPasswordHash()
        );

        // 2) 每个已知账号均完成 BCrypt 校验；禁用或锁定状态仍统一返回相同 401
        if (account.getAccountStatus() != AdminAccountStatusEnum.ACTIVE) {
            loginAttemptWriter.recordFailure(
                    account.getId(),
                    username,
                    AdminLoginFailureReasonEnum.ACCOUNT_DISABLED
            );
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        if (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
            loginAttemptWriter.recordFailure(
                    account.getId(),
                    username,
                    AdminLoginFailureReasonEnum.ACCOUNT_LOCKED
            );
            throw invalidCredentials();
        }

        // 3) 错误密码独立提交失败次数，第 5 次进入 15 分钟锁定
        if (!passwordMatches) {
            loginAttemptWriter.recordFailure(
                    account.getId(),
                    username,
                    AdminLoginFailureReasonEnum.INVALID_PASSWORD
            );
            throw invalidCredentials();
        }

        // 4) 成功状态提交后，使用同一账号 tokenVersion 签发访问令牌
        AdminAccount authenticated = loginAttemptWriter.recordSuccess(account.getId());
        AdminJwtService.IssuedToken issuedToken = jwtService.issue(authenticated);
        return new AdminLoginVo(
                issuedToken.accessToken(),
                AdminJwtService.TOKEN_TYPE,
                issuedToken.expiresAt(),
                authenticated.getId(),
                authenticated.getUsername(),
                authenticated.getRoleCode()
        );
    }

    @Override
    public void logout(Long adminId, String authenticatedUsername) {
        if (adminId == null || authenticatedUsername == null || authenticatedUsername.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        loginAttemptWriter.recordLogout(adminId, authenticatedUsername);
    }

    private void rejectUnknown(String username, String password) {
        passwordEncoder.matches(Objects.toString(password, ""), dummyPasswordHash);
        String safeUsername;
        try {
            safeUsername = credentialValidator.normalizeUsername(username);
        } catch (IllegalArgumentException exception) {
            safeUsername = "invalid-username";
        }
        loginAttemptWriter.recordFailure(
                null,
                safeUsername,
                AdminLoginFailureReasonEnum.UNKNOWN_USERNAME
        );
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
}
