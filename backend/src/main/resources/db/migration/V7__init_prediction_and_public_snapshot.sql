-- Versioned predictions and public snapshot metadata / 版本化预测与公开快照元数据

CREATE TABLE predictions (
    id                      BIGSERIAL PRIMARY KEY,
    match_id                BIGINT         NOT NULL,
    model_version           VARCHAR(64)    NOT NULL,
    feature_version         VARCHAR(64)    NOT NULL,
    generation_batch_id     VARCHAR(128)   NOT NULL,
    generation_batch_hash   CHAR(64)       NOT NULL,
    prediction_version      INTEGER        NOT NULL,
    home_win_prob           NUMERIC(7,6)   NOT NULL,
    draw_prob               NUMERIC(7,6)   NOT NULL,
    away_win_prob           NUMERIC(7,6)   NOT NULL,
    handicap_pick           VARCHAR(32)    NOT NULL,
    expected_total_goals    NUMERIC(5,2)   NOT NULL,
    confidence_level        VARCHAR(16)    NOT NULL,
    analysis_summary        VARCHAR(1000)  NOT NULL,
    generated_at            TIMESTAMPTZ    NOT NULL,
    prediction_status       VARCHAR(32)    NOT NULL DEFAULT 'DRAFT',
    publish_time            TIMESTAMPTZ,
    lock_time               TIMESTAMPTZ,
    prediction_hash         CHAR(64),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_predictions_match FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT uk_predictions_match_model_version UNIQUE (
        match_id,
        model_version,
        prediction_version
    ),
    CONSTRAINT uk_predictions_generation_batch UNIQUE (
        generation_batch_id,
        match_id,
        model_version
    ),
    CONSTRAINT ck_predictions_traceability_non_blank CHECK (
        BTRIM(model_version) <> ''
        AND BTRIM(feature_version) <> ''
        AND BTRIM(generation_batch_id) <> ''
    ),
    CONSTRAINT ck_predictions_generation_batch_hash CHECK (
        generation_batch_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_predictions_version_positive CHECK (prediction_version > 0),
    CONSTRAINT ck_predictions_probability_range CHECK (
        home_win_prob BETWEEN 0 AND 1
        AND draw_prob BETWEEN 0 AND 1
        AND away_win_prob BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_predictions_probability_sum CHECK (
        home_win_prob + draw_prob + away_win_prob BETWEEN 0.999999 AND 1.000001
    ),
    CONSTRAINT ck_predictions_handicap_pick CHECK (
        handicap_pick IN ('HOME_WIN', 'DRAW', 'AWAY_WIN')
    ),
    CONSTRAINT ck_predictions_expected_total_goals CHECK (expected_total_goals >= 0),
    CONSTRAINT ck_predictions_confidence_level CHECK (
        confidence_level IN ('LOW', 'MEDIUM', 'HIGH')
    ),
    CONSTRAINT ck_predictions_analysis_summary CHECK (BTRIM(analysis_summary) <> ''),
    CONSTRAINT ck_predictions_status CHECK (
        prediction_status IN ('DRAFT', 'PUBLISHED', 'LOCKED')
    ),
    CONSTRAINT ck_predictions_prediction_hash CHECK (
        prediction_hash IS NULL
        OR prediction_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_predictions_publish_lifecycle CHECK (
        (
            prediction_status = 'DRAFT'
            AND publish_time IS NULL
            AND lock_time IS NULL
            AND prediction_hash IS NULL
        )
        OR (
            prediction_status IN ('PUBLISHED', 'LOCKED')
            AND publish_time IS NOT NULL
            AND lock_time IS NOT NULL
            AND prediction_hash IS NOT NULL
            AND publish_time < lock_time
        )
    )
);

CREATE TABLE prediction_snapshots (
    id                      BIGSERIAL PRIMARY KEY,
    snapshot_date           DATE           NOT NULL,
    snapshot_version        INTEGER        NOT NULL,
    snapshot_status         VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    snapshot_hash           CHAR(64),
    storage_type            VARCHAR(32),
    object_key              VARCHAR(512),
    object_version          VARCHAR(128),
    file_url                VARCHAR(1024),
    content_type            VARCHAR(128),
    content_length          BIGINT,
    published_at            TIMESTAMPTZ,
    failure_reason          VARCHAR(1000),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prediction_snapshots_date_version UNIQUE (
        snapshot_date,
        snapshot_version
    ),
    CONSTRAINT ck_prediction_snapshots_version_positive CHECK (snapshot_version > 0),
    CONSTRAINT ck_prediction_snapshots_status CHECK (
        snapshot_status IN ('PENDING', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT ck_prediction_snapshots_hash CHECK (
        snapshot_hash IS NULL
        OR snapshot_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_prediction_snapshots_storage_fields CHECK (
        (storage_type IS NULL OR BTRIM(storage_type) <> '')
        AND (object_key IS NULL OR BTRIM(object_key) <> '')
        AND (object_version IS NULL OR BTRIM(object_version) <> '')
        AND (file_url IS NULL OR BTRIM(file_url) <> '')
        AND (content_type IS NULL OR BTRIM(content_type) <> '')
        AND (content_length IS NULL OR content_length >= 0)
    ),
    CONSTRAINT ck_prediction_snapshots_lifecycle CHECK (
        (
            snapshot_status = 'PENDING'
            AND snapshot_hash IS NULL
            AND storage_type IS NULL
            AND object_key IS NULL
            AND object_version IS NULL
            AND file_url IS NULL
            AND content_type IS NULL
            AND content_length IS NULL
            AND published_at IS NULL
            AND failure_reason IS NULL
        )
        OR (
            snapshot_status = 'PUBLISHED'
            AND snapshot_hash IS NOT NULL
            AND storage_type IS NOT NULL
            AND object_key IS NOT NULL
            AND file_url IS NOT NULL
            AND content_type IS NOT NULL
            AND content_length IS NOT NULL
            AND published_at IS NOT NULL
            AND failure_reason IS NULL
        )
        OR (
            snapshot_status = 'FAILED'
            AND published_at IS NULL
            AND failure_reason IS NOT NULL
            AND BTRIM(failure_reason) <> ''
        )
    )
);

CREATE INDEX idx_predictions_match_model_version_desc
    ON predictions (match_id, model_version, prediction_version DESC);

CREATE INDEX idx_predictions_status_lock_time
    ON predictions (prediction_status, lock_time);

CREATE INDEX idx_prediction_snapshots_status_date
    ON prediction_snapshots (snapshot_status, snapshot_date DESC);

COMMENT ON TABLE predictions IS 'Versioned model predictions retained as history / 保留历史的模型预测版本';
COMMENT ON COLUMN predictions.match_id IS 'Internal match id / 内部比赛 ID';
COMMENT ON COLUMN predictions.model_version IS 'Traceable model version / 可追溯模型版本';
COMMENT ON COLUMN predictions.feature_version IS 'Traceable feature definition version / 可追溯特征版本';
COMMENT ON COLUMN predictions.generation_batch_id IS 'External generation batch identity / 外部生成批次标识';
COMMENT ON COLUMN predictions.generation_batch_hash IS 'SHA-256 of the generation input batch / 生成输入批次 SHA-256';
COMMENT ON COLUMN predictions.prediction_version IS 'Monotonic version within match and model / 同比赛模型内递增版本';
COMMENT ON COLUMN predictions.home_win_prob IS 'Home win probability in [0,1] / 主胜概率 0～1';
COMMENT ON COLUMN predictions.draw_prob IS 'Draw probability in [0,1] / 平局概率 0～1';
COMMENT ON COLUMN predictions.away_win_prob IS 'Away win probability in [0,1] / 客胜概率 0～1';
COMMENT ON COLUMN predictions.handicap_pick IS 'Handicap win-draw-loss tendency / 让球胜平负倾向';
COMMENT ON COLUMN predictions.expected_total_goals IS 'Expected total goals / 预期总进球';
COMMENT ON COLUMN predictions.confidence_level IS 'Model confidence level code / 模型置信等级编码';
COMMENT ON COLUMN predictions.analysis_summary IS 'Public analysis summary / 公开分析摘要';
COMMENT ON COLUMN predictions.generated_at IS 'Model output generation time / 模型输出生成时间';
COMMENT ON COLUMN predictions.prediction_status IS 'Prediction publication lifecycle status / 预测发布生命周期状态';
COMMENT ON COLUMN predictions.publish_time IS 'First visibility time of this version / 当前版本首次公开时间';
COMMENT ON COLUMN predictions.lock_time IS 'Time after which this version is locked / 当前版本锁定时间';
COMMENT ON COLUMN predictions.prediction_hash IS 'SHA-256 of normalized published content / 规范化发布内容 SHA-256';

COMMENT ON TABLE prediction_snapshots IS 'Public prediction snapshot publication metadata / 公开预测快照发布元数据';
COMMENT ON COLUMN prediction_snapshots.snapshot_date IS 'Public snapshot business date / 公开快照业务日期';
COMMENT ON COLUMN prediction_snapshots.snapshot_version IS 'Publication version within snapshot date / 同快照日期内发布版本';
COMMENT ON COLUMN prediction_snapshots.snapshot_status IS 'Snapshot publication lifecycle status / 快照发布生命周期状态';
COMMENT ON COLUMN prediction_snapshots.snapshot_hash IS 'SHA-256 of canonical manifest bytes / 规范化清单字节 SHA-256';
COMMENT ON COLUMN prediction_snapshots.storage_type IS 'Snapshot storage implementation code / 快照存储实现编码';
COMMENT ON COLUMN prediction_snapshots.object_key IS 'Immutable storage object key / 不可覆盖存储对象键';
COMMENT ON COLUMN prediction_snapshots.object_version IS 'Optional object storage version / 可选对象存储版本';
COMMENT ON COLUMN prediction_snapshots.file_url IS 'Published snapshot location / 已发布快照地址';
COMMENT ON COLUMN prediction_snapshots.content_type IS 'Stored object content type / 存储对象内容类型';
COMMENT ON COLUMN prediction_snapshots.content_length IS 'Stored object size in bytes / 存储对象字节数';
COMMENT ON COLUMN prediction_snapshots.published_at IS 'Successful publication time / 成功发布时间';
COMMENT ON COLUMN prediction_snapshots.failure_reason IS 'Latest publication failure summary / 最近发布失败摘要';
