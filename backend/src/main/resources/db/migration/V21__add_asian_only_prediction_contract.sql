-- Asian-only prediction contract and per-market confidence / 亚盘专用预测与分项置信度契约

ALTER TABLE predictions
    DROP CONSTRAINT ck_predictions_asian_market_prediction,
    ALTER COLUMN home_win_prob DROP NOT NULL,
    ALTER COLUMN draw_prob DROP NOT NULL,
    ALTER COLUMN away_win_prob DROP NOT NULL,
    ALTER COLUMN handicap_pick DROP NOT NULL,
    ALTER COLUMN expected_total_goals DROP NOT NULL,
    ALTER COLUMN confidence_level DROP NOT NULL,
    ADD COLUMN prediction_type VARCHAR(16) NOT NULL DEFAULT 'SPORTTERY',
    ADD COLUMN asian_handicap_confidence_level VARCHAR(16),
    ADD COLUMN total_goals_confidence_level VARCHAR(16),
    ADD CONSTRAINT ck_predictions_type CHECK (
        prediction_type IN ('SPORTTERY', 'ASIAN')
    ),
    ADD CONSTRAINT ck_predictions_sporttery_core_by_type CHECK (
        (
            prediction_type = 'SPORTTERY'
            AND home_win_prob IS NOT NULL
            AND draw_prob IS NOT NULL
            AND away_win_prob IS NOT NULL
            AND handicap_pick IS NOT NULL
            AND expected_total_goals IS NOT NULL
            AND confidence_level IS NOT NULL
        )
        OR (
            prediction_type = 'ASIAN'
            AND home_win_prob IS NULL
            AND draw_prob IS NULL
            AND away_win_prob IS NULL
            AND handicap_pick IS NULL
            AND expected_total_goals IS NULL
            AND confidence_level IS NULL
        )
    ),
    ADD CONSTRAINT ck_predictions_asian_market_prediction_v21 CHECK (
        (
            prediction_type = 'SPORTTERY'
            AND (
                asian_odds_snapshot_id IS NULL
                AND asian_handicap_pick IS NULL
                AND total_goals_pick IS NULL
                AND asian_handicap_confidence_level IS NULL
                AND total_goals_confidence_level IS NULL
            OR (
                asian_odds_snapshot_id IS NOT NULL
                AND asian_handicap_pick IN ('HOME_COVER', 'AWAY_COVER')
                AND total_goals_pick IN ('OVER', 'UNDER')
                AND asian_handicap_confidence_level IS NULL
                AND total_goals_confidence_level IS NULL
            )
        )
        OR (
            prediction_type = 'ASIAN'
            AND asian_odds_snapshot_id IS NOT NULL
            AND (
                (asian_handicap_pick IS NULL AND asian_handicap_confidence_level IS NULL)
                OR (
                    asian_handicap_pick IN ('HOME_COVER', 'AWAY_COVER')
                    AND asian_handicap_confidence_level IS NOT NULL
                    AND asian_handicap_confidence_level IN ('MEDIUM', 'HIGH')
                )
            )
            AND (
                (total_goals_pick IS NULL AND total_goals_confidence_level IS NULL)
                OR (
                    total_goals_pick IN ('OVER', 'UNDER')
                    AND total_goals_confidence_level IS NOT NULL
                    AND total_goals_confidence_level IN ('MEDIUM', 'HIGH')
                )
            )
            AND (asian_handicap_pick IS NOT NULL OR total_goals_pick IS NOT NULL)
        )
    ));

COMMENT ON COLUMN predictions.prediction_type IS
    'Prediction product type / 预测产品类型：体彩或亚盘';
COMMENT ON COLUMN predictions.asian_handicap_confidence_level IS
    'Asian handicap direction confidence / 亚盘让球方向置信等级';
COMMENT ON COLUMN predictions.total_goals_confidence_level IS
    'Asian totals direction confidence / 亚盘大小球方向置信等级';

-- Protect the added fields while keeping existing SPORTTERY rows and their original snapshots valid.
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
                NEW.confidence_level, NEW.analysis_summary, NEW.generated_at
            ) IS DISTINCT FROM ROW(
                OLD.match_id, OLD.model_version, OLD.feature_version,
                OLD.generation_batch_id, OLD.generation_batch_hash, OLD.prediction_version,
                OLD.prediction_type, OLD.home_win_prob, OLD.draw_prob, OLD.away_win_prob,
                OLD.handicap_pick, OLD.expected_total_goals, OLD.asian_odds_snapshot_id,
                OLD.asian_handicap_pick, OLD.total_goals_pick,
                OLD.asian_handicap_confidence_level, OLD.total_goals_confidence_level,
                OLD.confidence_level, OLD.analysis_summary, OLD.generated_at
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
        NEW.publish_time, NEW.lock_time, NEW.prediction_hash, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.match_id, OLD.model_version, OLD.feature_version,
        OLD.generation_batch_id, OLD.generation_batch_hash, OLD.prediction_version,
        OLD.prediction_type, OLD.home_win_prob, OLD.draw_prob, OLD.away_win_prob,
        OLD.handicap_pick, OLD.expected_total_goals, OLD.asian_odds_snapshot_id,
        OLD.asian_handicap_pick, OLD.total_goals_pick,
        OLD.asian_handicap_confidence_level, OLD.total_goals_confidence_level,
        OLD.confidence_level, OLD.analysis_summary, OLD.generated_at,
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
