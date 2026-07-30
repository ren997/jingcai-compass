-- Provider normalization review metadata / 供应商联赛、球队标准化复核元数据
-- Existing mappings deliberately remain without inferred metadata / 既有映射不从赛事或原始载荷反推回填

ALTER TABLE provider_league_mappings
    ADD COLUMN external_display_name VARCHAR(128),
    ADD COLUMN external_normalized_key VARCHAR(128),
    ADD COLUMN external_scope VARCHAR(128);

ALTER TABLE provider_team_mappings
    ADD COLUMN external_display_name VARCHAR(128),
    ADD COLUMN external_normalized_key VARCHAR(128),
    ADD COLUMN external_scope VARCHAR(128);

CREATE INDEX idx_provider_league_mappings_review
    ON provider_league_mappings (mapping_status, updated_at DESC, id DESC);

CREATE INDEX idx_provider_team_mappings_review
    ON provider_team_mappings (mapping_status, updated_at DESC, id DESC);

COMMENT ON COLUMN provider_league_mappings.external_display_name
    IS 'Provider display label captured during live normalization / 实时标准化时采集的供应商展示名';
COMMENT ON COLUMN provider_league_mappings.external_normalized_key
    IS 'Normalized external label used for review only / 仅供复核的供应商规范化名称键';
COMMENT ON COLUMN provider_league_mappings.external_scope
    IS 'Optional provider-local identity scope / 可选的供应商内部身份作用域';
COMMENT ON COLUMN provider_team_mappings.external_display_name
    IS 'Provider display label captured during live normalization / 实时标准化时采集的供应商展示名';
COMMENT ON COLUMN provider_team_mappings.external_normalized_key
    IS 'Normalized external label used for review only / 仅供复核的供应商规范化名称键';
COMMENT ON COLUMN provider_team_mappings.external_scope
    IS 'Provider-local identity scope such as The Odds sport_key / 供应商内部身份作用域，例如 The Odds sport_key';
