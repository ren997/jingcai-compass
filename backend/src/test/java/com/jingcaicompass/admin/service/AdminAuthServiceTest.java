package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.dto.AdminLoginDto;
import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.enums.AdminLoginFailureReasonEnum;
import com.jingcaicompass.admin.enums.AdminRoleEnum;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.admin.vo.AdminLoginVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T06:00:00Z");

    @Mock
    private AdminAccountMapper adminAccountMapper;

    @Mock
    private AdminLoginAttemptWriter loginAttemptWriter;

    @Mock
    private AdminJwtService jwtService;

    private PasswordEncoder passwordEncoder;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new AdminAuthServiceImpl(
                adminAccountMapper,
                new AdminAccountCredentialValidator(),
                loginAttemptWriter,
                jwtService,
                passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void authenticatesActiveAccountAndReturnsIssuedToken() {
        AdminAccount account = account("admin", "correct-password");
        when(adminAccountMapper.selectOne(any())).thenReturn(account);
        when(loginAttemptWriter.recordSuccess(1L)).thenReturn(account);
        when(jwtService.issue(account)).thenReturn(new AdminJwtService.IssuedToken(
                "signed-token",
                NOW.plusSeconds(1800)
        ));

        AdminLoginVo result = service.login(new AdminLoginDto(" ADMIN ", "correct-password"));

        assertThat(result.accessToken()).isEqualTo("signed-token");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(result.adminId()).isEqualTo(1L);
        assertThat(result.username()).isEqualTo("admin");
        assertThat(result.role()).isEqualTo(AdminRoleEnum.ADMIN);
    }

    @Test
    void rejectsWrongPasswordWithUnifiedErrorAndRecordsFailure() {
        AdminAccount account = account("admin", "correct-password");
        when(adminAccountMapper.selectOne(any())).thenReturn(account);

        assertInvalidCredentials(() ->
                service.login(new AdminLoginDto("admin", "wrong-password")));
        verify(loginAttemptWriter).recordFailure(
                1L,
                "admin",
                AdminLoginFailureReasonEnum.INVALID_PASSWORD
        );
    }

    @Test
    void rejectsUnknownAndDisabledAccountsWithSamePublicError() {
        when(adminAccountMapper.selectOne(any())).thenReturn(null);

        assertInvalidCredentials(() ->
                service.login(new AdminLoginDto("missing-admin", "wrong-password")));
        verify(loginAttemptWriter).recordFailure(
                null,
                "missing-admin",
                AdminLoginFailureReasonEnum.UNKNOWN_USERNAME
        );

        AdminAccount disabled = account("disabled-admin", "correct-password");
        disabled.setAccountStatus(AdminAccountStatusEnum.DISABLED);
        when(adminAccountMapper.selectOne(any())).thenReturn(disabled);

        assertInvalidCredentials(() ->
                service.login(new AdminLoginDto("disabled-admin", "correct-password")));
        verify(loginAttemptWriter).recordFailure(
                1L,
                "disabled-admin",
                AdminLoginFailureReasonEnum.ACCOUNT_DISABLED
        );
    }

    @Test
    void rejectsAccountWhileTemporaryLockIsActive() {
        AdminAccount account = account("admin", "correct-password");
        account.setLockedUntil(NOW.plusSeconds(60));
        when(adminAccountMapper.selectOne(any())).thenReturn(account);

        assertInvalidCredentials(() ->
                service.login(new AdminLoginDto("admin", "correct-password")));
        verify(loginAttemptWriter).recordFailure(
                1L,
                "admin",
                AdminLoginFailureReasonEnum.ACCOUNT_LOCKED
        );
    }

    @Test
    void delegatesLogoutUsingAuthenticatedIdentity() {
        service.logout(1L, "admin");

        verify(loginAttemptWriter).recordLogout(1L, "admin");
    }

    private AdminAccount account(String username, String password) {
        AdminAccount account = new AdminAccount();
        account.setId(1L);
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setRoleCode(AdminRoleEnum.ADMIN);
        account.setAccountStatus(AdminAccountStatusEnum.ACTIVE);
        account.setFailedLoginCount(0);
        account.setTokenVersion(0L);
        return account;
    }

    private void assertInvalidCredentials(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
    }
}
