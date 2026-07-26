package com.jingcaicompass.admin.service;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** 在 Security 异常处理阶段安全追加访问拒绝审计。 */
@Component
public class AdminSecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityAuditService.class);
    private static final String ANONYMOUS = "ANONYMOUS";

    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    public AdminSecurityAuditService(ObjectProvider<AuditLogService> auditLogServiceProvider) {
        this.auditLogServiceProvider = auditLogServiceProvider;
    }

    /** 记录 401/403，不保存 Authorization、Cookie 或请求体。 */
    public void recordAccessDenied(
            HttpServletRequest request,
            Authentication authentication,
            String reason
    ) {
        AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
        if (auditLogService == null) {
            return;
        }
        String operator = authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : ANONYMOUS;
        String targetId = (request.getMethod() + " " + request.getRequestURI());
        if (targetId.length() > 128) {
            targetId = targetId.substring(0, 128);
        }
        try {
            auditLogService.append(
                    operator,
                    AuditTargetTypeEnum.SECURITY_REQUEST,
                    targetId,
                    AuditActionTypeEnum.ACCESS_DENIED,
                    null,
                    null,
                    reason
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "security audit append failed operator={} target={} reason={}",
                    operator,
                    targetId,
                    reason,
                    exception
            );
        }
    }
}
