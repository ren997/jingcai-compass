package com.jingcaicompass.audit.service;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;

/** 只追加审计写入。 */
public interface AuditLogService {

    /**
     * 追加一条审计记录。
     *
     * @param operatorId 操作者
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param actionType 操作类型
     * @param fieldName 字段名，可空
     * @param oldValue 旧值快照，可空
     * @param newValue 新值快照，可空
     */
    void append(
            String operatorId,
            AuditTargetTypeEnum targetType,
            String targetId,
            AuditActionTypeEnum actionType,
            String fieldName,
            String oldValue,
            String newValue
    );
}
