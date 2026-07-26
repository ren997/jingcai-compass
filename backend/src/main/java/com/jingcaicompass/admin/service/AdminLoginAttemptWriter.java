package com.jingcaicompass.admin.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.enums.AdminLoginFailureReasonEnum;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 独立提交登录状态和认证审计，防止随后返回 401 时回滚安全记录。 */
@Component
@ConditionalOnBean(DataSource.class)
public class AdminLoginAttemptWriter {

    private static final String ANONYMOUS = "ANONYMOUS";

    private final AdminAccountMapper adminAccountMapper;
    private final AuditLogService auditLogService;
    private final AdminSecurityProperties properties;
    private final Clock clock;

    public AdminLoginAttemptWriter(
            AdminAccountMapper adminAccountMapper,
            AuditLogService auditLogService,
            AdminSecurityProperties properties,
            Clock clock
    ) {
        this.adminAccountMapper = adminAccountMapper;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 提交失败次数、临时锁定和失败审计。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            Long adminId,
            String normalizedUsername,
            AdminLoginFailureReasonEnum reason
    ) {
        // 1) 未知账号只写匿名审计，禁止凭空创建账号状态
        if (adminId == null) {
            appendLoginAudit(
                    ANONYMOUS,
                    normalizedUsername,
                    AuditActionTypeEnum.LOGIN_FAILED,
                    reason.getCode()
            );
            return;
        }

        // 2) 锁定已知账号行，串行化并发失败计数
        AdminAccount account = requireLockedAccount(adminId);
        Instant now = clock.instant();
        if (reason.isCountTowardLock()
                && account.getAccountStatus() == AdminAccountStatusEnum.ACTIVE) {
            int currentCount = account.getFailedLoginCount() == null
                    ? 0
                    : account.getFailedLoginCount();
            if (account.getLockedUntil() != null && !account.getLockedUntil().isAfter(now)) {
                currentCount = 0;
                account.setLockedUntil(null);
            }
            int nextCount = currentCount + 1;
            account.setFailedLoginCount(nextCount);
            if (nextCount >= properties.login().maxFailedAttempts()) {
                account.setLockedUntil(now.plus(properties.login().lockDuration()));
            }
            int rows = adminAccountMapper.update(
                    null,
                    new UpdateWrapper<AdminAccount>()
                            .eq("id", account.getId())
                            .set("failed_login_count", account.getFailedLoginCount())
                            .set("locked_until", account.getLockedUntil())
                            .set("updated_at", now)
            );
            if (rows != 1) {
                throw new IllegalStateException("administrator failure state update conflict");
            }
        }

        // 3) 审计保存内部原因和锁定截止时间，但不保存密码或 Token
        String detail = reason.getCode();
        if (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
            detail += ";lockedUntil=" + account.getLockedUntil();
        }
        appendLoginAudit(
                ANONYMOUS,
                String.valueOf(account.getId()),
                AuditActionTypeEnum.LOGIN_FAILED,
                detail
        );
    }

    /** 提交成功登录状态并返回锁定后的最新账号快照。 */
    @Transactional
    public AdminAccount recordSuccess(Long adminId) {
        // 1) 锁定账号，确保状态和 tokenVersion 与即将签发的 Token 一致
        AdminAccount account = requireLockedAccount(adminId);
        if (account.getAccountStatus() != AdminAccountStatusEnum.ACTIVE) {
            throw new IllegalStateException("administrator account is not active");
        }

        // 2) 清零连续失败和临时锁，记录最近成功登录时间
        account.setFailedLoginCount(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(clock.instant());
        int rows = adminAccountMapper.update(
                null,
                new UpdateWrapper<AdminAccount>()
                        .eq("id", account.getId())
                        .set("failed_login_count", 0)
                        .set("locked_until", null)
                        .set("last_login_at", account.getLastLoginAt())
                        .set("updated_at", account.getLastLoginAt())
        );
        if (rows != 1) {
            throw new IllegalStateException("administrator login state update conflict");
        }

        // 3) 在同一事务追加成功登录审计
        appendLoginAudit(
                account.getUsername(),
                String.valueOf(account.getId()),
                AuditActionTypeEnum.LOGIN_SUCCESS,
                "SUCCESS"
        );
        return account;
    }

    /** 递增 tokenVersion，使该管理员全部旧 JWT 立即失效。 */
    @Transactional
    public void recordLogout(Long adminId, String authenticatedUsername) {
        // 1) 锁定账号并保护版本上溢
        AdminAccount account = requireLockedAccount(adminId);
        if (account.getTokenVersion() == null || account.getTokenVersion() == Long.MAX_VALUE) {
            throw new IllegalStateException("administrator token version exhausted");
        }

        // 2) 持久化新版本后追加退出审计
        account.setTokenVersion(account.getTokenVersion() + 1);
        int rows = adminAccountMapper.update(
                null,
                new UpdateWrapper<AdminAccount>()
                        .eq("id", account.getId())
                        .set("token_version", account.getTokenVersion())
                        .set("updated_at", clock.instant())
        );
        if (rows != 1) {
            throw new IllegalStateException("administrator logout state update conflict");
        }
        appendLoginAudit(
                authenticatedUsername,
                String.valueOf(account.getId()),
                AuditActionTypeEnum.LOGOUT,
                "tokenVersion=" + account.getTokenVersion()
        );
    }

    private AdminAccount requireLockedAccount(Long adminId) {
        AdminAccount account = adminAccountMapper.selectByIdForUpdate(adminId);
        if (account == null) {
            throw new IllegalStateException("administrator account not found");
        }
        return account;
    }

    private void appendLoginAudit(
            String operatorId,
            String targetId,
            AuditActionTypeEnum action,
            String detail
    ) {
        auditLogService.append(
                operatorId,
                AuditTargetTypeEnum.ADMIN_ACCOUNT,
                targetId,
                action,
                null,
                null,
                detail
        );
    }
}
