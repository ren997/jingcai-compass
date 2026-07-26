package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminAccountBootstrapRunnerTest {

    private static final Instant NOW = Instant.parse("2026-07-26T06:00:00Z");

    @Mock
    private AdminAccountMapper adminAccountMapper;

    @Mock
    private AuditLogService auditLogService;

    @Test
    void createsFirstAccountWithNormalizedUsernameAndBcryptHash() {
        when(adminAccountMapper.selectCount(null)).thenReturn(0L);
        when(adminAccountMapper.insert(any(AdminAccount.class))).thenAnswer(invocation -> {
            invocation.<AdminAccount>getArgument(0).setId(7L);
            return 1;
        });
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        AdminAccountBootstrapRunner runner = runner(
                " First.Admin ",
                "bootstrap-password-123",
                encoder
        );

        runner.run(null);

        ArgumentCaptor<AdminAccount> captor = ArgumentCaptor.forClass(AdminAccount.class);
        verify(adminAccountMapper).insert(captor.capture());
        AdminAccount saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("first.admin");
        assertThat(saved.getPasswordHash()).isNotEqualTo("bootstrap-password-123");
        assertThat(encoder.matches("bootstrap-password-123", saved.getPasswordHash())).isTrue();
        assertThat(saved.getRoleCode()).isEqualTo(AdminRoleEnum.ADMIN);
        assertThat(saved.getAccountStatus()).isEqualTo(AdminAccountStatusEnum.ACTIVE);
        assertThat(saved.getPasswordUpdatedAt()).isEqualTo(NOW);
        verify(auditLogService).append(
                "first.admin",
                AuditTargetTypeEnum.ADMIN_ACCOUNT,
                "7",
                AuditActionTypeEnum.ADMIN_BOOTSTRAP,
                null,
                null,
                "role=ADMIN"
        );
    }

    @Test
    void existingAccountPreventsSilentPasswordReplacement() {
        when(adminAccountMapper.selectCount(null)).thenReturn(1L);

        runner("admin", "different-password-123", new BCryptPasswordEncoder(4)).run(null);

        verify(adminAccountMapper, never()).insert(any(AdminAccount.class));
        verify(auditLogService, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void emptyTableRequiresBothBootstrapValues() {
        when(adminAccountMapper.selectCount(null)).thenReturn(0L);

        assertThatThrownBy(() ->
                runner("", "", new BCryptPasswordEncoder(4)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_BOOTSTRAP_USERNAME")
                .hasMessageContaining("ADMIN_BOOTSTRAP_PASSWORD");
    }

    private AdminAccountBootstrapRunner runner(
            String username,
            String password,
            BCryptPasswordEncoder encoder
    ) {
        AdminSecurityProperties properties = new AdminSecurityProperties(
                new AdminSecurityProperties.JwtProperties(
                        "unused",
                        "issuer",
                        "audience",
                        Duration.ofMinutes(30)
                ),
                new AdminSecurityProperties.LoginProperties(5, Duration.ofMinutes(15)),
                new AdminSecurityProperties.BootstrapProperties(username, password)
        );
        return new AdminAccountBootstrapRunner(
                adminAccountMapper,
                new AdminAccountCredentialValidator(),
                encoder,
                properties,
                auditLogService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
