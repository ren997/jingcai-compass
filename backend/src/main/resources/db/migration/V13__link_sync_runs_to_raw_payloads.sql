-- Exact sync-run to persisted raw-payload relationship / 同步运行与原始载荷精确关联

CREATE TABLE data_sync_run_payloads (
    id                  BIGSERIAL PRIMARY KEY,
    sync_run_id         BIGINT      NOT NULL,
    raw_data_payload_id BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_data_sync_run_payloads_run_payload UNIQUE (sync_run_id, raw_data_payload_id),
    CONSTRAINT fk_data_sync_run_payloads_run
        FOREIGN KEY (sync_run_id) REFERENCES data_sync_runs (id),
    CONSTRAINT fk_data_sync_run_payloads_payload
        FOREIGN KEY (raw_data_payload_id) REFERENCES raw_data_payloads (id)
);

CREATE INDEX idx_data_sync_run_payloads_payload
    ON data_sync_run_payloads (raw_data_payload_id, sync_run_id);

COMMENT ON TABLE data_sync_run_payloads IS
    'Exact run-to-payload links, including deduplicated payload reuse / 同步运行与原始载荷精确关联（支持去重载荷复用）';
COMMENT ON COLUMN data_sync_run_payloads.sync_run_id IS
    'Synchronization run ID / 同步运行 ID';
COMMENT ON COLUMN data_sync_run_payloads.raw_data_payload_id IS
    'Persisted raw payload ID / 已持久化原始载荷 ID';
