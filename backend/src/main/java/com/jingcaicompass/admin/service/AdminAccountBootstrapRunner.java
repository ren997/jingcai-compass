package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.enums.AdminRoleEnum;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** admin_accounts 为空时，从环境变量一次性创建第一位管理员。 */
@Component
@ConditionalOnBean(DataSource.class)
public class AdminAccountBootstrapRunner implements ApplicationRunner {

    private final AdminAccountMapper adminAccountMapper;
    private final AdminAccountCredentialValidator credentialValidator;
    private final PasswordEncoder passwordEncoder;
    private final AdminSecurityProperties properties;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminAccountBootstrapRunner(
            AdminAccountMapper adminAccountMapper,
            AdminAccountCredentialValidator credentialValidator,
            PasswordEncoder passwordEncoder,
            AdminSecurityProperties properties,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.adminAccountMapper = adminAccountMapper;
        this.credentialValidator = credentialValidator;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        // 1) 表中已有任意管理员时立即退出，禁止启动参数静默新增或改密
        if (adminAccountMapper.selectCount(null) > 0) {
            return;
        }

        // 2) 空表必须同时提供首次引导用户名和密码
        String configuredUsername = properties.bootstrap().username();
        String configuredPassword = properties.bootstrap().password();
        if (!StringUtils.hasText(configuredUsername) || !StringUtils.hasText(configuredPassword)) {
            throw new IllegalStateException(
                    "ADMIN_BOOTSTRAP_USERNAME and ADMIN_BOOTSTRAP_PASSWORD are required "
                            + "when admin_accounts is empty"
            );
        }
        String username = credentialValidator.normalizeUsername(configuredUsername);
        credentialValidator.validateBootstrapPassword(configuredPassword);

        // 3) 只持久化 BCrypt 哈希；并发实例由大小写不敏感唯一索引兜底
        AdminAccount account = new AdminAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(configuredPassword));
        account.setRoleCode(AdminRoleEnum.ADMIN);
        account.setAccountStatus(AdminAccountStatusEnum.ACTIVE);
        account.setFailedLoginCount(0);
        account.setTokenVersion(0L);
        account.setPasswordUpdatedAt(clock.instant());
        try {
            adminAccountMapper.insert(account);
        } catch (DataIntegrityViolationException exception) {
            if (adminAccountMapper.selectCount(null) > 0) {
                return;
            }
            throw exception;
        }

        // 4) 追加首位管理员创建审计，不记录原始密码或密码哈希
        auditLogService.append(
                username,
                AuditTargetTypeEnum.ADMIN_ACCOUNT,
                String.valueOf(account.getId()),
                AuditActionTypeEnum.ADMIN_BOOTSTRAP,
                null,
                null,
                "role=" + AdminRoleEnum.ADMIN.getCode()
        );
    }
}
