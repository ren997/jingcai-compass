package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.enums.AdminLoginFailureReasonEnum;
import com.jingcaicompass.admin.enums.AdminRoleEnum;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminLoginAttemptWriterTest {

    private static final Instant NOW = Instant.parse("2026-07-26T06:00:00Z");

    @Mock
    private AdminAccountMapper adminAccountMapper;

    @Mock
    private AuditLogService auditLogService;

    private AdminLoginAttemptWriter writer;

    @BeforeEach
    void setUp() {
        AdminSecurityProperties properties = new AdminSecurityProperties(
                new AdminSecurityProperties.JwtProperties(
                        "unused",
                        "issuer",
                        "audience",
                        Duration.ofMinutes(30)
                ),
                new AdminSecurityProperties.LoginProperties(5, Duration.ofMinutes(15)),
                new AdminSecurityProperties.BootstrapProperties("", "")
        );
        writer = new AdminLoginAttemptWriter(
                adminAccountMapper,
                auditLogService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void fifthWrongPasswordLocksAccountForFifteenMinutes() {
        AdminAccount account = account();
        account.setFailedLoginCount(4);
        when(adminAccountMapper.selectByIdForUpdate(1L)).thenReturn(account);
        when(adminAccountMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        writer.recordFailure(1L, "admin", AdminLoginFailureReasonEnum.INVALID_PASSWORD);

        assertThat(account.getFailedLoginCount()).isEqualTo(5);
        assertThat(account.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        verify(auditLogService).append(
                "ANONYMOUS",
                AuditTargetTypeEnum.ADMIN_ACCOUNT,
                "1",
                AuditActionTypeEnum.LOGIN_FAILED,
                null,
                null,
                "INVALID_PASSWORD;lockedUntil=" + NOW.plus(Duration.ofMinutes(15))
        );
    }

    @Test
    void expiredLockStartsAnewFailureSequence() {
        AdminAccount account = account();
        account.setFailedLoginCount(5);
        account.setLockedUntil(NOW.minusSeconds(1));
        when(adminAccountMapper.selectByIdForUpdate(1L)).thenReturn(account);
        when(adminAccountMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        writer.recordFailure(1L, "admin", AdminLoginFailureReasonEnum.INVALID_PASSWORD);

        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void successfulLoginClearsFailuresAndLogoutRevokesOldTokens() {
        AdminAccount account = account();
        account.setFailedLoginCount(3);
        account.setLockedUntil(NOW.minusSeconds(1));
        when(adminAccountMapper.selectByIdForUpdate(1L)).thenReturn(account);
        when(adminAccountMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        AdminAccount authenticated = writer.recordSuccess(1L);

        assertThat(authenticated.getFailedLoginCount()).isZero();
        assertThat(authenticated.getLockedUntil()).isNull();
        assertThat(authenticated.getLastLoginAt()).isEqualTo(NOW);

        writer.recordLogout(1L, "admin");

        assertThat(account.getTokenVersion()).isEqualTo(1L);
        verify(auditLogService).append(
                "admin",
                AuditTargetTypeEnum.ADMIN_ACCOUNT,
                "1",
                AuditActionTypeEnum.LOGOUT,
                null,
                null,
                "tokenVersion=1"
        );
    }

    private AdminAccount account() {
        AdminAccount account = new AdminAccount();
        account.setId(1L);
        account.setUsername("admin");
        account.setRoleCode(AdminRoleEnum.ADMIN);
        account.setAccountStatus(AdminAccountStatusEnum.ACTIVE);
        account.setFailedLoginCount(0);
        account.setTokenVersion(0L);
        return account;
    }
}
