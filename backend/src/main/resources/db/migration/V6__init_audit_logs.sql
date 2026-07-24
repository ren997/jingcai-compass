-- Append-only operator audit logs / 只追加的操作审计

CREATE TABLE audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    operator_id  VARCHAR(64)  NOT NULL,
    target_type  VARCHAR(64)  NOT NULL,
    target_id    VARCHAR(128) NOT NULL,
    action_type  VARCHAR(64)  NOT NULL,
    field_name   VARCHAR(64),
    old_value    TEXT,
    new_value    TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_target ON audit_logs (target_type, target_id, created_at DESC);
CREATE INDEX idx_audit_logs_operator_created ON audit_logs (operator_id, created_at DESC);

COMMENT ON TABLE audit_logs IS 'Append-only operator audit trail / 只追加的操作审计';
COMMENT ON COLUMN audit_logs.operator_id IS 'Operator identity / 操作者标识';
COMMENT ON COLUMN audit_logs.target_type IS 'Audited entity type code / 被审计实体类型编码';
COMMENT ON COLUMN audit_logs.target_id IS 'Audited entity id / 被审计实体 ID';
COMMENT ON COLUMN audit_logs.action_type IS 'Action type code / 操作类型编码';
COMMENT ON COLUMN audit_logs.field_name IS 'Changed field name when applicable / 变更字段名';
COMMENT ON COLUMN audit_logs.old_value IS 'Previous value snapshot / 变更前快照';
COMMENT ON COLUMN audit_logs.new_value IS 'New value snapshot / 变更后快照';
