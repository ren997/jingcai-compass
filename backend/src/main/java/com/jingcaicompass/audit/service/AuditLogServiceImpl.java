package com.jingcaicompass.audit.service;

import com.jingcaicompass.audit.entity.AuditLog;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 审计写入实现：仅 insert，不更新历史行。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    public AuditLogServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void append(
            String operatorId,
            AuditTargetTypeEnum targetType,
            String targetId,
            AuditActionTypeEnum actionType,
            String fieldName,
            String oldValue,
            String newValue
    ) {
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        if (!StringUtils.hasText(operatorId)) {
            throw new IllegalArgumentException("operatorId must not be blank");
        }
        if (!StringUtils.hasText(targetId)) {
            throw new IllegalArgumentException("targetId must not be blank");
        }

        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId.trim());
        log.setTargetType(targetType);
        log.setTargetId(targetId.trim());
        log.setActionType(actionType);
        log.setFieldName(fieldName);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        auditLogMapper.insert(log);
    }
}
