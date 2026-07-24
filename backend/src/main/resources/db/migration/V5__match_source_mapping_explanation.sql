-- Enrich match_source_mappings with explanation and candidate list / 比赛映射补充解释与候选

ALTER TABLE match_source_mappings
    ADD COLUMN mapping_explanation TEXT,
    ADD COLUMN mapping_candidates  JSONB;

COMMENT ON COLUMN match_source_mappings.mapping_explanation IS
    'Human-readable mapping reasons / 可读映射解释';
COMMENT ON COLUMN match_source_mappings.mapping_candidates IS
    'Scored candidate list JSON / 打分后的候选列表 JSON';
