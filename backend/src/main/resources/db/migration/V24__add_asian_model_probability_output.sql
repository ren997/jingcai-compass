-- Asian model probability output for ROI and Brier statistics / 亚盘模型概率输出，供 ROI 与 Brier 统计

-- All ten columns share one type on purpose: the generator emits six-decimal values
-- (AsianMarketMath.PROBABILITY_SCALE = 6), so a narrower scale would round silently
-- on write. Semantics are carried by the CHECK constraints instead of by the type.
-- 十列统一类型是有意的：生成侧按 6 位小数产出，更窄的标度会在写入时静默四舍五入。
-- 语义一律由 CHECK 约束承载，不再依赖列类型。
ALTER TABLE predictions
    ADD COLUMN lambda_home NUMERIC(9,6),
    ADD COLUMN lambda_away NUMERIC(9,6),
    ADD COLUMN model_cover_prob NUMERIC(9,6),
    ADD COLUMN market_cover_prob NUMERIC(9,6),
    ADD COLUMN edge_cover NUMERIC(9,6),
    ADD COLUMN model_over_prob NUMERIC(9,6),
    ADD COLUMN market_over_prob NUMERIC(9,6),
    ADD COLUMN edge_over NUMERIC(9,6),
    ADD COLUMN handicap_dispersion NUMERIC(9,6),
    ADD COLUMN totals_dispersion NUMERIC(9,6),
    ADD CONSTRAINT ck_predictions_asian_model_output_range CHECK (
        (lambda_home IS NULL OR lambda_home >= 0)
        AND (lambda_away IS NULL OR lambda_away >= 0)
        AND (model_cover_prob IS NULL OR model_cover_prob BETWEEN 0 AND 1)
        AND (market_cover_prob IS NULL OR market_cover_prob BETWEEN 0 AND 1)
        AND (model_over_prob IS NULL OR model_over_prob BETWEEN 0 AND 1)
        AND (market_over_prob IS NULL OR market_over_prob BETWEEN 0 AND 1)
        AND (edge_cover IS NULL OR edge_cover BETWEEN -1 AND 1)
        AND (edge_over IS NULL OR edge_over BETWEEN -1 AND 1)
        AND (handicap_dispersion IS NULL OR handicap_dispersion BETWEEN 0 AND 1)
        AND (totals_dispersion IS NULL OR totals_dispersion BETWEEN 0 AND 1)
    ),
    -- Model output is produced per market, so the columns of one market must live and
    -- die together; a half-written market would score against probabilities that were
    -- never the ones the model projected.
    -- 模型输出按市场整体产出，同一市场的列必须同生同灭；只写一半会让结算拿"概率 A"
    -- 去打分"模型本没算过的方向 B"。
    ADD CONSTRAINT ck_predictions_asian_model_output_pairs CHECK (
        (
            (lambda_home IS NULL AND lambda_away IS NULL)
            OR (lambda_home IS NOT NULL AND lambda_away IS NOT NULL)
        )
        AND (
            (
                model_cover_prob IS NULL
                AND market_cover_prob IS NULL
                AND edge_cover IS NULL
            )
            OR (
                model_cover_prob IS NOT NULL
                AND market_cover_prob IS NOT NULL
                AND edge_cover IS NOT NULL
            )
        )
        AND (
            (
                model_over_prob IS NULL
                AND market_over_prob IS NULL
                AND edge_over IS NULL
                AND totals_dispersion IS NULL
            )
            OR (
                model_over_prob IS NOT NULL
                AND market_over_prob IS NOT NULL
                AND edge_over IS NOT NULL
                AND totals_dispersion IS NOT NULL
            )
        )
    ),
    -- The columns are asian-only. Nulling them for SPORTTERY keeps a legacy row from
    -- ever carrying asian probabilities into the statistics.
    -- 这些列只属于亚盘。竞彩行强制全 NULL，避免遗留行携带亚盘概率进入统计。
    ADD CONSTRAINT ck_predictions_asian_model_output_by_type CHECK (
        prediction_type = 'ASIAN'
        OR (
            lambda_home IS NULL
            AND lambda_away IS NULL
            AND model_cover_prob IS NULL
            AND market_cover_prob IS NULL
            AND edge_cover IS NULL
            AND model_over_prob IS NULL
            AND market_over_prob IS NULL
            AND edge_over IS NULL
            AND handicap_dispersion IS NULL
            AND totals_dispersion IS NULL
        )
    );

COMMENT ON COLUMN predictions.lambda_home IS
    'Poisson expected goals for the home side, solved from the two main lines / 由两条主盘反解得到的泊松期望进球，主队';
COMMENT ON COLUMN predictions.lambda_away IS
    'Poisson expected goals for the away side, solved from the two main lines / 由两条主盘反解得到的泊松期望进球，客队';
COMMENT ON COLUMN predictions.model_cover_prob IS
    'Model probability that the home side covers the frozen handicap line / 模型认为主队赢下冻结让球线的概率（首边口径，看客队时取 1 − p）';
COMMENT ON COLUMN predictions.market_cover_prob IS
    'Cross-bookmaker devigged probability that the home side covers the frozen handicap line / 跨庄去水后主队赢盘的共识概率（首边口径）';
COMMENT ON COLUMN predictions.edge_cover IS
    'model_cover_prob minus market_cover_prob; structurally zero on the handicap main line / 让球方向模型概率减共识概率，在主盘上结构性为 0';
COMMENT ON COLUMN predictions.model_over_prob IS
    'Model probability of goals over the frozen totals line / 模型认为总进球高过冻结大小球线的概率（首边口径，看小球时取 1 − p）';
COMMENT ON COLUMN predictions.market_over_prob IS
    'Cross-bookmaker devigged probability of goals over the frozen totals line / 跨庄去水后大球的共识概率（首边口径）';
COMMENT ON COLUMN predictions.edge_over IS
    'model_over_prob minus market_over_prob; non-zero only when the frozen line differs from the totals main line / 大小球方向模型概率减共识概率，仅在冻结线不等于大小球主盘时非零';
COMMENT ON COLUMN predictions.handicap_dispersion IS
    'Population standard deviation of per-bookmaker devigged handicap probabilities / 各庄让球去水概率的总体标准差，衡量跨庄分歧';
COMMENT ON COLUMN predictions.totals_dispersion IS
    'Population standard deviation of per-bookmaker devigged totals probabilities / 各庄大小球去水概率的总体标准差，衡量跨庄分歧';

-- The model output is the basis of the ROI and Brier statistics, so it carries the same
-- immutability as the published direction. The function body is copied verbatim from
-- V21 with the ten columns appended to both ROW lists in identical order; a missing
-- column would allow the probabilities behind a published pick to be rewritten after
-- the fact, and mismatched order would make every publish fail.
-- 模型输出是 ROI 与 Brier 统计的计账依据，必须与已发布方向同等不可变。函数体逐字照抄
-- V21，仅在两处 ROW 列表末尾按相同顺序追加十列：漏列等于允许事后改写已发布预测背后的
-- 概率，顺序错位则会让所有发布都失败。
CREATE OR REPLACE FUNCTION protect_published_prediction()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.prediction_status = 'DRAFT' THEN
        IF NEW.prediction_status NOT IN ('DRAFT', 'PUBLISHED') THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                CONSTRAINT = 'ck_predictions_status_transition',
                MESSAGE = 'prediction status can only transition from DRAFT to PUBLISHED';
        END IF;

        IF NEW.prediction_status = 'PUBLISHED'
            AND ROW(
                NEW.match_id, NEW.model_version, NEW.feature_version,
                NEW.generation_batch_id, NEW.generation_batch_hash, NEW.prediction_version,
                NEW.prediction_type, NEW.home_win_prob, NEW.draw_prob, NEW.away_win_prob,
                NEW.handicap_pick, NEW.expected_total_goals, NEW.asian_odds_snapshot_id,
                NEW.asian_handicap_pick, NEW.total_goals_pick,
                NEW.asian_handicap_confidence_level, NEW.total_goals_confidence_level,
                NEW.confidence_level, NEW.analysis_summary, NEW.generated_at,
                NEW.lambda_home, NEW.lambda_away,
                NEW.model_cover_prob, NEW.market_cover_prob, NEW.edge_cover,
                NEW.model_over_prob, NEW.market_over_prob, NEW.edge_over,
                NEW.handicap_dispersion, NEW.totals_dispersion
            ) IS DISTINCT FROM ROW(
                OLD.match_id, OLD.model_version, OLD.feature_version,
                OLD.generation_batch_id, OLD.generation_batch_hash, OLD.prediction_version,
                OLD.prediction_type, OLD.home_win_prob, OLD.draw_prob, OLD.away_win_prob,
                OLD.handicap_pick, OLD.expected_total_goals, OLD.asian_odds_snapshot_id,
                OLD.asian_handicap_pick, OLD.total_goals_pick,
                OLD.asian_handicap_confidence_level, OLD.total_goals_confidence_level,
                OLD.confidence_level, OLD.analysis_summary, OLD.generated_at,
                OLD.lambda_home, OLD.lambda_away,
                OLD.model_cover_prob, OLD.market_cover_prob, OLD.edge_cover,
                OLD.model_over_prob, OLD.market_over_prob, OLD.edge_over,
                OLD.handicap_dispersion, OLD.totals_dispersion
            )
        THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                CONSTRAINT = 'ck_predictions_publish_content_unchanged',
                MESSAGE = 'prediction core content cannot change during publication';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(
        NEW.match_id, NEW.model_version, NEW.feature_version,
        NEW.generation_batch_id, NEW.generation_batch_hash, NEW.prediction_version,
        NEW.prediction_type, NEW.home_win_prob, NEW.draw_prob, NEW.away_win_prob,
        NEW.handicap_pick, NEW.expected_total_goals, NEW.asian_odds_snapshot_id,
        NEW.asian_handicap_pick, NEW.total_goals_pick,
        NEW.asian_handicap_confidence_level, NEW.total_goals_confidence_level,
        NEW.confidence_level, NEW.analysis_summary, NEW.generated_at,
        NEW.lambda_home, NEW.lambda_away,
        NEW.model_cover_prob, NEW.market_cover_prob, NEW.edge_cover,
        NEW.model_over_prob, NEW.market_over_prob, NEW.edge_over,
        NEW.handicap_dispersion, NEW.totals_dispersion,
        NEW.publish_time, NEW.lock_time, NEW.prediction_hash, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.match_id, OLD.model_version, OLD.feature_version,
        OLD.generation_batch_id, OLD.generation_batch_hash, OLD.prediction_version,
        OLD.prediction_type, OLD.home_win_prob, OLD.draw_prob, OLD.away_win_prob,
        OLD.handicap_pick, OLD.expected_total_goals, OLD.asian_odds_snapshot_id,
        OLD.asian_handicap_pick, OLD.total_goals_pick,
        OLD.asian_handicap_confidence_level, OLD.total_goals_confidence_level,
        OLD.confidence_level, OLD.analysis_summary, OLD.generated_at,
        OLD.lambda_home, OLD.lambda_away,
        OLD.model_cover_prob, OLD.market_cover_prob, OLD.edge_cover,
        OLD.model_over_prob, OLD.market_over_prob, OLD.edge_over,
        OLD.handicap_dispersion, OLD.totals_dispersion,
        OLD.publish_time, OLD.lock_time, OLD.prediction_hash, OLD.created_at
    )
    THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_predictions_immutable_after_publish',
            MESSAGE = 'published prediction content is immutable';
    END IF;

    IF OLD.prediction_status = 'PUBLISHED' THEN
        IF NEW.prediction_status NOT IN ('PUBLISHED', 'LOCKED') THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                CONSTRAINT = 'ck_predictions_status_transition',
                MESSAGE = 'published prediction can only transition to LOCKED';
        END IF;
        IF NEW.prediction_status = 'LOCKED' AND OLD.lock_time > CURRENT_TIMESTAMP THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                CONSTRAINT = 'ck_predictions_lock_deadline',
                MESSAGE = 'prediction cannot be locked before lock_time';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.prediction_status = 'LOCKED' AND NEW.prediction_status <> 'LOCKED' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_predictions_status_transition',
            MESSAGE = 'locked prediction cannot transition to another status';
    END IF;
    RETURN NEW;
END;
$$;
