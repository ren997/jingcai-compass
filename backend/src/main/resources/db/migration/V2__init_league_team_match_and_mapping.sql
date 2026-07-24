-- Standard league/team/match entities and provider mappings / 标准联赛球队比赛与供应商映射

CREATE TABLE leagues (
    id              BIGSERIAL PRIMARY KEY,
    name_zh         VARCHAR(128) NOT NULL,
    name_en         VARCHAR(128),
    country_code    VARCHAR(16),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE teams (
    id              BIGSERIAL PRIMARY KEY,
    name_zh         VARCHAR(128) NOT NULL,
    name_en         VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE matches (
    id                  BIGSERIAL PRIMARY KEY,
    lottery_match_no    VARCHAR(32)  NOT NULL,
    lottery_date        DATE         NOT NULL,
    league_id           BIGINT,
    home_team_id        BIGINT,
    away_team_id        BIGINT,
    league_name         VARCHAR(128) NOT NULL,
    home_team_name      VARCHAR(128) NOT NULL,
    away_team_name      VARCHAR(128) NOT NULL,
    kickoff_time        TIMESTAMPTZ  NOT NULL,
    match_status        VARCHAR(32)  NOT NULL,
    home_score          INTEGER,
    away_score          INTEGER,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_matches_lottery_match_no_date UNIQUE (lottery_match_no, lottery_date),
    CONSTRAINT fk_matches_league FOREIGN KEY (league_id) REFERENCES leagues (id),
    CONSTRAINT fk_matches_home_team FOREIGN KEY (home_team_id) REFERENCES teams (id),
    CONSTRAINT fk_matches_away_team FOREIGN KEY (away_team_id) REFERENCES teams (id),
    CONSTRAINT ck_matches_match_status CHECK (
        match_status IN (
            'SCHEDULED',
            'LOCKED',
            'IN_PROGRESS',
            'FINISHED',
            'POSTPONED',
            'CANCELLED',
            'ABANDONED'
        )
    ),
    CONSTRAINT ck_matches_home_away_team_diff CHECK (
        home_team_id IS NULL
        OR away_team_id IS NULL
        OR home_team_id <> away_team_id
    ),
    CONSTRAINT ck_matches_scores_non_negative CHECK (
        (home_score IS NULL OR home_score >= 0)
        AND (away_score IS NULL OR away_score >= 0)
    )
);

CREATE TABLE provider_league_mappings (
    id                   BIGSERIAL PRIMARY KEY,
    league_id            BIGINT       NOT NULL,
    provider_code        VARCHAR(64)  NOT NULL,
    external_league_id   VARCHAR(128) NOT NULL,
    mapping_status       VARCHAR(32)  NOT NULL,
    mapping_confidence   NUMERIC(5,4),
    mapping_method       VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_provider_league_mappings_provider_external UNIQUE (provider_code, external_league_id),
    CONSTRAINT fk_provider_league_mappings_league FOREIGN KEY (league_id) REFERENCES leagues (id),
    CONSTRAINT ck_provider_league_mappings_status CHECK (
        mapping_status IN ('PENDING', 'AUTO_CONFIRMED', 'MANUAL_CONFIRMED', 'REJECTED')
    ),
    CONSTRAINT ck_provider_league_mappings_confidence CHECK (
        mapping_confidence IS NULL
        OR (mapping_confidence >= 0 AND mapping_confidence <= 1)
    )
);

CREATE TABLE provider_team_mappings (
    id                   BIGSERIAL PRIMARY KEY,
    team_id              BIGINT       NOT NULL,
    provider_code        VARCHAR(64)  NOT NULL,
    external_team_id     VARCHAR(128) NOT NULL,
    mapping_status       VARCHAR(32)  NOT NULL,
    mapping_confidence   NUMERIC(5,4),
    mapping_method       VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_provider_team_mappings_provider_external UNIQUE (provider_code, external_team_id),
    CONSTRAINT fk_provider_team_mappings_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT ck_provider_team_mappings_status CHECK (
        mapping_status IN ('PENDING', 'AUTO_CONFIRMED', 'MANUAL_CONFIRMED', 'REJECTED')
    ),
    CONSTRAINT ck_provider_team_mappings_confidence CHECK (
        mapping_confidence IS NULL
        OR (mapping_confidence >= 0 AND mapping_confidence <= 1)
    )
);

CREATE TABLE match_source_mappings (
    id                      BIGSERIAL PRIMARY KEY,
    match_id                BIGINT       NOT NULL,
    provider_code           VARCHAR(64)  NOT NULL,
    external_match_id       VARCHAR(128) NOT NULL,
    external_league_id      VARCHAR(128),
    external_home_team_id   VARCHAR(128),
    external_away_team_id   VARCHAR(128),
    mapping_status          VARCHAR(32)  NOT NULL,
    mapping_confidence      NUMERIC(5,4),
    mapping_method          VARCHAR(64),
    confirmed_by            VARCHAR(64),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_match_source_mappings_provider_external UNIQUE (provider_code, external_match_id),
    CONSTRAINT fk_match_source_mappings_match FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT ck_match_source_mappings_status CHECK (
        mapping_status IN ('PENDING', 'AUTO_CONFIRMED', 'MANUAL_CONFIRMED', 'REJECTED')
    ),
    CONSTRAINT ck_match_source_mappings_confidence CHECK (
        mapping_confidence IS NULL
        OR (mapping_confidence >= 0 AND mapping_confidence <= 1)
    )
);

CREATE INDEX idx_matches_lottery_date ON matches (lottery_date DESC);
CREATE INDEX idx_matches_kickoff_time ON matches (kickoff_time);
CREATE INDEX idx_matches_match_status ON matches (match_status);
CREATE INDEX idx_provider_league_mappings_league ON provider_league_mappings (league_id);
CREATE INDEX idx_provider_team_mappings_team ON provider_team_mappings (team_id);
CREATE INDEX idx_match_source_mappings_match ON match_source_mappings (match_id);

COMMENT ON TABLE leagues IS 'Standard league dictionary / 标准联赛字典';
COMMENT ON COLUMN leagues.name_zh IS 'Chinese display name / 中文显示名';
COMMENT ON COLUMN leagues.name_en IS 'English display name / 英文显示名';
COMMENT ON COLUMN leagues.country_code IS 'Optional country or region code / 可选国家或地区编码';

COMMENT ON TABLE teams IS 'Standard team dictionary / 标准球队字典';
COMMENT ON COLUMN teams.name_zh IS 'Chinese display name / 中文显示名';
COMMENT ON COLUMN teams.name_en IS 'English display name / 英文显示名';

COMMENT ON TABLE matches IS 'Internal match aggregate keyed by lottery identity / 以体彩编号锚定的内部比赛';
COMMENT ON COLUMN matches.lottery_match_no IS 'Official lottery match number / 体彩官方比赛编号';
COMMENT ON COLUMN matches.lottery_date IS 'Lottery business date Asia/Shanghai / 竞彩业务日期';
COMMENT ON COLUMN matches.match_status IS 'Match status code / 比赛状态编码';
COMMENT ON COLUMN matches.league_name IS 'Display league name before normalization / 标准化前联赛展示名';
COMMENT ON COLUMN matches.home_team_name IS 'Display home team name before normalization / 标准化前主队展示名';
COMMENT ON COLUMN matches.away_team_name IS 'Display away team name before normalization / 标准化前客队展示名';

COMMENT ON TABLE provider_league_mappings IS 'Provider league id to standard league mapping / 供应商联赛映射';
COMMENT ON TABLE provider_team_mappings IS 'Provider team id to standard team mapping / 供应商球队映射';
COMMENT ON TABLE match_source_mappings IS 'Provider match id to internal match mapping / 供应商比赛映射';
COMMENT ON COLUMN match_source_mappings.mapping_status IS 'Mapping confirmation status / 映射确认状态';
COMMENT ON COLUMN match_source_mappings.mapping_confidence IS 'Mapping confidence in [0,1] / 映射置信度 0～1';
