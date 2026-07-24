package com.jingcaicompass.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import java.time.Instant;
import lombok.Data;

/** 只追加的操作审计记录。 */
@Data
@TableName("audit_logs")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者标识 */
    private String operatorId;

    /**
     * 被审计实体类型
     *
     * @see AuditTargetTypeEnum#DESC
     */
    private AuditTargetTypeEnum targetType;

    /** 被审计实体 ID */
    private String targetId;

    /**
     * 操作类型
     *
     * @see AuditActionTypeEnum#DESC
     */
    private AuditActionTypeEnum actionType;

    /** 变更字段名 */
    private String fieldName;

    /** 变更前快照 */
    private String oldValue;

    /** 变更后快照 */
    private String newValue;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
