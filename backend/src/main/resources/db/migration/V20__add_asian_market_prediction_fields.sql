-- Asian market prediction directions and immutable input snapshot / 亚盘方向预测与不可变输入快照

ALTER TABLE asian_odds_snapshots
    ADD CONSTRAINT uk_asian_odds_snapshots_id_match UNIQUE (id, match_id);

ALTER TABLE predictions
    ADD COLUMN asian_odds_snapshot_id BIGINT,
    ADD COLUMN asian_handicap_pick VARCHAR(32),
    ADD COLUMN total_goals_pick VARCHAR(16),
    ADD CONSTRAINT fk_predictions_asian_odds_snapshot_match
        FOREIGN KEY (asian_odds_snapshot_id, match_id)
        REFERENCES asian_odds_snapshots (id, match_id),
    ADD CONSTRAINT ck_predictions_asian_market_prediction CHECK (
        (
            asian_odds_snapshot_id IS NULL
            AND asian_handicap_pick IS NULL
            AND total_goals_pick IS NULL
        )
        OR (
            asian_odds_snapshot_id IS NOT NULL
            AND asian_handicap_pick IS NOT NULL
            AND asian_handicap_pick IN ('HOME_COVER', 'AWAY_COVER')
            AND total_goals_pick IS NOT NULL
            AND total_goals_pick IN ('OVER', 'UNDER')
        )
    );

CREATE INDEX idx_predictions_asian_odds_snapshot
    ON predictions (asian_odds_snapshot_id)
    WHERE asian_odds_snapshot_id IS NOT NULL;

COMMENT ON COLUMN predictions.asian_odds_snapshot_id IS
    'Confirmed complete Asian market snapshot used at generation / 生成时使用的已确认完整亚盘快照';
COMMENT ON COLUMN predictions.asian_handicap_pick IS
    'Asian handicap cover direction / 亚盘让球赢盘方向';
COMMENT ON COLUMN predictions.total_goals_pick IS
    'Asian totals direction / 亚盘大小球方向';

-- The original V9 trigger predates the three immutable Asian market fields.
-- Replacing its function keeps old V1 rows valid while protecting V2 content.
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
                NEW.match_id,
                NEW.model_version,
                NEW.feature_version,
                NEW.generation_batch_id,
                NEW.generation_batch_hash,
                NEW.prediction_version,
                NEW.home_win_prob,
                NEW.draw_prob,
                NEW.away_win_prob,
                NEW.handicap_pick,
                NEW.expected_total_goals,
                NEW.asian_odds_snapshot_id,
                NEW.asian_handicap_pick,
                NEW.total_goals_pick,
                NEW.confidence_level,
                NEW.analysis_summary,
                NEW.generated_at
            ) IS DISTINCT FROM ROW(
                OLD.match_id,
                OLD.model_version,
                OLD.feature_version,
                OLD.generation_batch_id,
                OLD.generation_batch_hash,
                OLD.prediction_version,
                OLD.home_win_prob,
                OLD.draw_prob,
                OLD.away_win_prob,
                OLD.handicap_pick,
                OLD.expected_total_goals,
                OLD.asian_odds_snapshot_id,
                OLD.asian_handicap_pick,
                OLD.total_goals_pick,
                OLD.confidence_level,
                OLD.analysis_summary,
                OLD.generated_at
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
        NEW.match_id,
        NEW.model_version,
        NEW.feature_version,
        NEW.generation_batch_id,
        NEW.generation_batch_hash,
        NEW.prediction_version,
        NEW.home_win_prob,
        NEW.draw_prob,
        NEW.away_win_prob,
        NEW.handicap_pick,
        NEW.expected_total_goals,
        NEW.asian_odds_snapshot_id,
        NEW.asian_handicap_pick,
        NEW.total_goals_pick,
        NEW.confidence_level,
        NEW.analysis_summary,
        NEW.generated_at,
        NEW.publish_time,
        NEW.lock_time,
        NEW.prediction_hash,
        NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.match_id,
        OLD.model_version,
        OLD.feature_version,
        OLD.generation_batch_id,
        OLD.generation_batch_hash,
        OLD.prediction_version,
        OLD.home_win_prob,
        OLD.draw_prob,
        OLD.away_win_prob,
        OLD.handicap_pick,
        OLD.expected_total_goals,
        OLD.asian_odds_snapshot_id,
        OLD.asian_handicap_pick,
        OLD.total_goals_pick,
        OLD.confidence_level,
        OLD.analysis_summary,
        OLD.generated_at,
        OLD.publish_time,
        OLD.lock_time,
        OLD.prediction_hash,
        OLD.created_at
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

        IF NEW.prediction_status = 'LOCKED'
            AND OLD.lock_time > CURRENT_TIMESTAMP
        THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                CONSTRAINT = 'ck_predictions_lock_deadline',
                MESSAGE = 'prediction cannot be locked before lock_time';
        END IF;

        RETURN NEW;
    END IF;

    IF OLD.prediction_status = 'LOCKED'
        AND NEW.prediction_status <> 'LOCKED'
    THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_predictions_status_transition',
            MESSAGE = 'locked prediction cannot transition to another status';
    END IF;

    RETURN NEW;
END;
$$;
