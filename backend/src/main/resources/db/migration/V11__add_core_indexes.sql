-- Query-backed indexes for current facts and settlement history / 当前赛果与结算历史查询索引

CREATE INDEX idx_match_result_facts_current_eligible
    ON match_result_facts (match_id)
    WHERE is_current
      AND fact_status IN ('FINAL', 'VOID');

CREATE INDEX idx_settlements_current_match_fact
    ON settlements (match_fact_id)
    WHERE is_current;

COMMENT ON INDEX idx_match_result_facts_current_eligible IS
    'Current settlement-eligible fact lookup by match / 按比赛查询当前可结算事实';
COMMENT ON INDEX idx_settlements_current_match_fact IS
    'Current settlements referencing one fact / 按赛果查询当前结算';
COMMENT ON INDEX uk_settlements_prediction_market_version IS
    'Settlement history by prediction and market / 按预测与市场查询结算历史';
