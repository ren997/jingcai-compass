-- Manually confirmed name aliases for leagues and teams / 联赛与球队人工确认别名
-- Distinct from provider_*_mappings which bind provider external IDs /
-- 与供应商外部 ID 映射表分工：本表仅存人工确认的名称别名

CREATE TABLE league_aliases (
    id                BIGSERIAL PRIMARY KEY,
    league_id         BIGINT       NOT NULL,
    alias_raw         VARCHAR(128) NOT NULL,
    alias_normalized  VARCHAR(128) NOT NULL,
    source            VARCHAR(64),
    confirmed_by      VARCHAR(64),
    confirmed_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_league_aliases_normalized UNIQUE (alias_normalized),
    CONSTRAINT fk_league_aliases_league FOREIGN KEY (league_id) REFERENCES leagues (id)
);

CREATE TABLE team_aliases (
    id                BIGSERIAL PRIMARY KEY,
    team_id           BIGINT       NOT NULL,
    alias_raw         VARCHAR(128) NOT NULL,
    alias_normalized  VARCHAR(128) NOT NULL,
    source            VARCHAR(64),
    confirmed_by      VARCHAR(64),
    confirmed_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_team_aliases_normalized UNIQUE (alias_normalized),
    CONSTRAINT fk_team_aliases_team FOREIGN KEY (team_id) REFERENCES teams (id)
);

CREATE INDEX idx_league_aliases_league ON league_aliases (league_id);
CREATE INDEX idx_team_aliases_team ON team_aliases (team_id);

COMMENT ON TABLE league_aliases IS 'Manually confirmed league name aliases / 人工确认的联赛名称别名';
COMMENT ON COLUMN league_aliases.league_id IS 'Standard league id / 标准联赛 ID';
COMMENT ON COLUMN league_aliases.alias_raw IS 'Original alias display text / 原始别名展示文本';
COMMENT ON COLUMN league_aliases.alias_normalized IS 'Normalized alias key for exact match / 用于精确匹配的规范化别名 key';
COMMENT ON COLUMN league_aliases.source IS 'Alias source note / 别名来源说明';
COMMENT ON COLUMN league_aliases.confirmed_by IS 'Confirmer identity / 确认人';
COMMENT ON COLUMN league_aliases.confirmed_at IS 'Confirmation timestamp / 确认时间';

COMMENT ON TABLE team_aliases IS 'Manually confirmed team name aliases / 人工确认的球队名称别名';
COMMENT ON COLUMN team_aliases.team_id IS 'Standard team id / 标准球队 ID';
COMMENT ON COLUMN team_aliases.alias_raw IS 'Original alias display text / 原始别名展示文本';
COMMENT ON COLUMN team_aliases.alias_normalized IS 'Normalized alias key for exact match / 用于精确匹配的规范化别名 key';
COMMENT ON COLUMN team_aliases.source IS 'Alias source note / 别名来源说明';
COMMENT ON COLUMN team_aliases.confirmed_by IS 'Confirmer identity / 确认人';
COMMENT ON COLUMN team_aliases.confirmed_at IS 'Confirmation timestamp / 确认时间';
