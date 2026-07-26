-- Published prediction lifecycle and immutability guard / 已发布预测生命周期与不可变保护

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

CREATE TRIGGER trg_predictions_protect_published
    BEFORE UPDATE ON predictions
    FOR EACH ROW
    EXECUTE FUNCTION protect_published_prediction();

COMMENT ON FUNCTION protect_published_prediction() IS
    'Protect published prediction lifecycle and immutable content / 保护已发布预测生命周期与不可变内容';
