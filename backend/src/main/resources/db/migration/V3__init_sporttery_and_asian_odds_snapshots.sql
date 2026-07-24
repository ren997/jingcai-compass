-- Append-only sporttery pool and asian odds snapshots / 只追加的体彩池与亚盘快照

CREATE TABLE sporttery_pool_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    match_id            BIGINT         NOT NULL,
    lottery_match_no    VARCHAR(32)    NOT NULL,
    lottery_date        DATE           NOT NULL,
    official_handicap   NUMERIC(6,2),
    had_home_sp         NUMERIC(10,4),
    had_draw_sp         NUMERIC(10,4),
    had_away_sp         NUMERIC(10,4),
    hhad_home_sp        NUMERIC(10,4),
    hhad_draw_sp        NUMERIC(10,4),
    hhad_away_sp        NUMERIC(10,4),
    sell_status         VARCHAR(64),
    captured_at         TIMESTAMPTZ    NOT NULL,
    provider_updated_at TIMESTAMPTZ,
    raw_payload_hash    CHAR(64)       NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sporttery_pool_snapshots_dedupe UNIQUE (match_id, captured_at, raw_payload_hash),
    CONSTRAINT fk_sporttery_pool_snapshots_match FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT ck_sporttery_pool_snapshots_payload_hash CHECK (raw_payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sporttery_pool_snapshots_sp_non_negative CHECK (
        (had_home_sp IS NULL OR had_home_sp >= 0)
        AND (had_draw_sp IS NULL OR had_draw_sp >= 0)
        AND (had_away_sp IS NULL OR had_away_sp >= 0)
        AND (hhad_home_sp IS NULL OR hhad_home_sp >= 0)
        AND (hhad_draw_sp IS NULL OR hhad_draw_sp >= 0)
        AND (hhad_away_sp IS NULL OR hhad_away_sp >= 0)
    )
);

CREATE TABLE asian_odds_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    match_id            BIGINT         NOT NULL,
    provider_code       VARCHAR(64)    NOT NULL,
    bookmaker_code      VARCHAR(64)    NOT NULL,
    handicap_line       NUMERIC(6,2)   NOT NULL,
    home_odds           NUMERIC(10,4)  NOT NULL,
    away_odds           NUMERIC(10,4)  NOT NULL,
    total_line          NUMERIC(6,2),
    over_odds           NUMERIC(10,4),
    under_odds          NUMERIC(10,4),
    snapshot_type       VARCHAR(32)    NOT NULL,
    captured_at         TIMESTAMPTZ    NOT NULL,
    provider_updated_at TIMESTAMPTZ,
    raw_payload_hash    CHAR(64)       NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_asian_odds_snapshots_dedupe UNIQUE (
        match_id,
        provider_code,
        bookmaker_code,
        captured_at,
        handicap_line
    ),
    CONSTRAINT fk_asian_odds_snapshots_match FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT ck_asian_odds_snapshots_snapshot_type CHECK (
        snapshot_type IN ('FIRST_SEEN', 'PRE_KICKOFF', 'OTHER')
    ),
    CONSTRAINT ck_asian_odds_snapshots_payload_hash CHECK (raw_payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_asian_odds_snapshots_odds_non_negative CHECK (
        home_odds >= 0
        AND away_odds >= 0
        AND (over_odds IS NULL OR over_odds >= 0)
        AND (under_odds IS NULL OR under_odds >= 0)
    ),
    CONSTRAINT ck_asian_odds_snapshots_totals_complete CHECK (
        (total_line IS NULL AND over_odds IS NULL AND under_odds IS NULL)
        OR (total_line IS NOT NULL AND over_odds IS NOT NULL AND under_odds IS NOT NULL)
    )
);

CREATE INDEX idx_sporttery_pool_snapshots_match_captured
    ON sporttery_pool_snapshots (match_id, captured_at DESC);

CREATE INDEX idx_asian_odds_snapshots_match_captured
    ON asian_odds_snapshots (match_id, captured_at DESC);

COMMENT ON TABLE sporttery_pool_snapshots IS 'Append-only China Sporttery pool/SP snapshots / 只追加的体彩比赛池与 SP 快照';
COMMENT ON COLUMN sporttery_pool_snapshots.official_handicap IS 'Official handicap line / 官方让球';
COMMENT ON COLUMN sporttery_pool_snapshots.had_home_sp IS 'HAD home SP / 胜平负主胜 SP';
COMMENT ON COLUMN sporttery_pool_snapshots.had_draw_sp IS 'HAD draw SP / 胜平负平局 SP';
COMMENT ON COLUMN sporttery_pool_snapshots.had_away_sp IS 'HAD away SP / 胜平负客胜 SP';
COMMENT ON COLUMN sporttery_pool_snapshots.hhad_home_sp IS 'HHAD home SP / 让球胜平负主胜 SP';
COMMENT ON COLUMN sporttery_pool_snapshots.hhad_draw_sp IS 'HHAD draw SP / 让球胜平负平局 SP';
COMMENT ON COLUMN sporttery_pool_snapshots.hhad_away_sp IS 'HHAD away SP / 让球胜平负客胜 SP';
COMMENT ON COLUMN sporttery_pool_snapshots.sell_status IS 'Sales status at capture / 采集时销售状态';
COMMENT ON COLUMN sporttery_pool_snapshots.raw_payload_hash IS 'SHA-256 hex of related raw payload / 关联原始载荷 SHA-256';

COMMENT ON TABLE asian_odds_snapshots IS 'Append-only asian handicap + totals snapshots on one row / 一行同时存亚盘让球与大小球的只追加快照';
COMMENT ON COLUMN asian_odds_snapshots.bookmaker_code IS 'Bookmaker or odds source code / 博彩公司或盘口来源编码';
COMMENT ON COLUMN asian_odds_snapshots.handicap_line IS 'Asian handicap line / 亚洲让球盘口';
COMMENT ON COLUMN asian_odds_snapshots.home_odds IS 'Home side odds / 主队水位';
COMMENT ON COLUMN asian_odds_snapshots.away_odds IS 'Away side odds / 客队水位';
COMMENT ON COLUMN asian_odds_snapshots.total_line IS 'Totals goal line / 大小球盘口线';
COMMENT ON COLUMN asian_odds_snapshots.over_odds IS 'Over odds / 大球水位';
COMMENT ON COLUMN asian_odds_snapshots.under_odds IS 'Under odds / 小球水位';
COMMENT ON COLUMN asian_odds_snapshots.snapshot_type IS 'Snapshot type code / 快照类型编码';
COMMENT ON COLUMN asian_odds_snapshots.raw_payload_hash IS 'SHA-256 hex of related raw payload / 关联原始载荷 SHA-256';
