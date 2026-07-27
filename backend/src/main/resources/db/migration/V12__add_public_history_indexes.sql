-- Public history and statistics query indexes / 公开历史与统计查询索引

CREATE INDEX idx_matches_history_lottery_league_kickoff
    ON matches (lottery_date DESC, league_id, kickoff_time DESC, id DESC);

CREATE INDEX idx_predictions_history_public_model_match
    ON predictions (prediction_status, model_version, match_id, publish_time DESC, id DESC)
    WHERE prediction_status IN ('PUBLISHED', 'LOCKED');

CREATE INDEX idx_settlements_current_market_status_prediction
    ON settlements (market_type, settlement_status, prediction_id)
    WHERE is_current;

COMMENT ON INDEX idx_matches_history_lottery_league_kickoff IS
    'Stable public history order and league/date filters / 公开历史稳定排序与联赛日期筛选';
COMMENT ON INDEX idx_predictions_history_public_model_match IS
    'Public prediction visibility and model filter / 公开预测可见性与模型筛选';
COMMENT ON INDEX idx_settlements_current_market_status_prediction IS
    'Current market settlement status filter / 当前市场结算状态筛选';
