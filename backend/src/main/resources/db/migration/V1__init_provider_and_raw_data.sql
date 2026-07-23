-- Provider registry and raw sync storage / Provider 注册与原始同步存储

CREATE TABLE data_providers (
    id              BIGSERIAL PRIMARY KEY,
    provider_code   VARCHAR(64)  NOT NULL,
    provider_name   VARCHAR(128) NOT NULL,
    category        VARCHAR(32)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    base_url        VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_data_providers_provider_code UNIQUE (provider_code),
    CONSTRAINT ck_data_providers_category CHECK (
        category IN ('SPORTTERY', 'ASIAN_ODDS', 'OTHER')
    )
);

CREATE TABLE raw_data_payloads (
    id                   BIGSERIAL PRIMARY KEY,
    provider_code        VARCHAR(64)  NOT NULL,
    data_type            VARCHAR(64)  NOT NULL,
    request_key          VARCHAR(256) NOT NULL,
    requested_at         TIMESTAMPTZ  NOT NULL,
    provider_updated_at  TIMESTAMPTZ,
    http_status          INTEGER,
    payload              JSONB        NOT NULL,
    payload_hash         CHAR(64)     NOT NULL,
    parse_status         VARCHAR(32)  NOT NULL,
    parse_error          TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_raw_data_payloads_dedupe UNIQUE (provider_code, data_type, request_key, payload_hash),
    CONSTRAINT ck_raw_data_payloads_data_type CHECK (
        data_type IN ('SPORTTERY_POOL', 'SPORTTERY_RESULT', 'ASIAN_ODDS', 'OTHER')
    ),
    CONSTRAINT ck_raw_data_payloads_parse_status CHECK (
        parse_status IN ('PENDING', 'SUCCESS', 'FAILED')
    ),
    CONSTRAINT ck_raw_data_payloads_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE data_sync_runs (
    id              BIGSERIAL PRIMARY KEY,
    provider_code   VARCHAR(64)  NOT NULL,
    data_type       VARCHAR(64)  NOT NULL,
    sync_status     VARCHAR(32)  NOT NULL,
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    fetched_count   INTEGER      NOT NULL DEFAULT 0,
    success_count   INTEGER      NOT NULL DEFAULT 0,
    failure_count   INTEGER      NOT NULL DEFAULT 0,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    quota_cost      INTEGER      NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_data_sync_runs_data_type CHECK (
        data_type IN ('SPORTTERY_POOL', 'SPORTTERY_RESULT', 'ASIAN_ODDS', 'OTHER')
    ),
    CONSTRAINT ck_data_sync_runs_sync_status CHECK (
        sync_status IN ('RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL')
    ),
    CONSTRAINT ck_data_sync_runs_counts CHECK (
        fetched_count >= 0
        AND success_count >= 0
        AND failure_count >= 0
        AND retry_count >= 0
        AND quota_cost >= 0
    )
);

CREATE INDEX idx_raw_data_payloads_provider_requested
    ON raw_data_payloads (provider_code, requested_at DESC);

CREATE INDEX idx_data_sync_runs_provider_started
    ON data_sync_runs (provider_code, started_at DESC);

INSERT INTO data_providers (provider_code, provider_name, category, enabled, base_url)
VALUES
    ('CHINA_SPORTTERY', '中国体彩网', 'SPORTTERY', TRUE, 'https://webapi.sporttery.cn'),
    ('STUB', 'Stub 演示数据源', 'OTHER', TRUE, NULL),
    ('THE_ODDS_API', 'The Odds API', 'ASIAN_ODDS', FALSE, 'https://api.the-odds-api.com');

COMMENT ON TABLE data_providers IS 'External provider registry without secrets / 外部 Provider 注册表（不含密钥）';
COMMENT ON COLUMN data_providers.provider_code IS 'Stable provider business code / 稳定 Provider 业务编码';
COMMENT ON COLUMN data_providers.category IS 'Provider category code / Provider 分类编码';
COMMENT ON COLUMN data_providers.enabled IS 'Whether provider is enabled / Provider 是否启用';
COMMENT ON COLUMN data_providers.base_url IS 'Non-secret base URL / 不含密钥的基础地址';

COMMENT ON TABLE raw_data_payloads IS 'Raw provider response payloads / Provider 原始响应载荷';
COMMENT ON COLUMN raw_data_payloads.payload IS 'Raw JSON payload / 原始 JSON 载荷';
COMMENT ON COLUMN raw_data_payloads.payload_hash IS 'SHA-256 hex digest of payload / 载荷 SHA-256 十六进制摘要';
COMMENT ON COLUMN raw_data_payloads.parse_status IS 'Parse status code / 解析状态编码';

COMMENT ON TABLE data_sync_runs IS 'Provider synchronization run records / Provider 同步运行记录';
COMMENT ON COLUMN data_sync_runs.sync_status IS 'Sync run status code / 同步运行状态编码';
COMMENT ON COLUMN data_sync_runs.quota_cost IS 'Quota credits consumed by the run / 本轮消耗额度';
