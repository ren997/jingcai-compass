-- Controlled manual match result facts / 受控人工赛果事实

ALTER TABLE raw_data_payloads
    DROP CONSTRAINT ck_raw_data_payloads_data_type;

ALTER TABLE raw_data_payloads
    ADD CONSTRAINT ck_raw_data_payloads_data_type CHECK (
        data_type IN ('SPORTTERY_POOL', 'SPORTTERY_RESULT', 'ASIAN_ODDS', 'MANUAL_RESULT', 'OTHER')
    );

ALTER TABLE match_result_facts
    ADD COLUMN result_source VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL',
    ADD COLUMN source_note VARCHAR(500),
    ADD COLUMN entry_reason VARCHAR(500),
    ADD COLUMN entered_by VARCHAR(128);

ALTER TABLE match_result_facts
    ADD CONSTRAINT ck_match_result_facts_source CHECK (
        result_source IN ('OFFICIAL', 'MANUAL')
    ),
    ADD CONSTRAINT ck_match_result_facts_manual_metadata CHECK (
        (
            result_source = 'OFFICIAL'
            AND source_note IS NULL
            AND entry_reason IS NULL
            AND entered_by IS NULL
        )
        OR (
            result_source = 'MANUAL'
            AND BTRIM(source_note) <> ''
            AND BTRIM(entry_reason) <> ''
            AND BTRIM(entered_by) <> ''
        )
    );

CREATE OR REPLACE FUNCTION validate_match_result_fact_payload()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    payload_data_type VARCHAR(64);
    payload_provider_code VARCHAR(64);
BEGIN
    SELECT data_type, provider_code
    INTO payload_data_type, payload_provider_code
    FROM raw_data_payloads
    WHERE id = NEW.raw_data_payload_id;

    IF NEW.result_source = 'OFFICIAL'
        AND payload_data_type = 'SPORTTERY_RESULT' THEN
        RETURN NEW;
    END IF;

    IF NEW.result_source = 'MANUAL'
        AND payload_data_type = 'MANUAL_RESULT'
        AND payload_provider_code = 'MANUAL_ENTRY' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION USING
        ERRCODE = '23514',
        CONSTRAINT = 'ck_match_result_facts_source_payload',
        MESSAGE = 'match result fact source does not match its raw payload';
END;
$$;

CREATE OR REPLACE FUNCTION protect_match_result_fact()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_match_result_facts_append_only',
            MESSAGE = 'match result facts cannot be deleted';
    END IF;

    IF OLD.is_current = TRUE
        AND NEW.is_current = FALSE
        AND ROW(
            NEW.match_id,
            NEW.fact_version,
            NEW.supersedes_fact_version,
            NEW.fact_status,
            NEW.match_status,
            NEW.home_score,
            NEW.away_score,
            NEW.raw_data_payload_id,
            NEW.provider_updated_at,
            NEW.result_source,
            NEW.source_note,
            NEW.entry_reason,
            NEW.entered_by,
            NEW.created_at
        ) IS NOT DISTINCT FROM ROW(
            OLD.match_id,
            OLD.fact_version,
            OLD.supersedes_fact_version,
            OLD.fact_status,
            OLD.match_status,
            OLD.home_score,
            OLD.away_score,
            OLD.raw_data_payload_id,
            OLD.provider_updated_at,
            OLD.result_source,
            OLD.source_note,
            OLD.entry_reason,
            OLD.entered_by,
            OLD.created_at
        )
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION USING
        ERRCODE = '23514',
        CONSTRAINT = 'ck_match_result_facts_append_only',
        MESSAGE = 'match result fact content is immutable; only current marker can transition true to false';
END;
$$;

COMMENT ON TABLE match_result_facts IS 'Versioned official or controlled manual match results / 版本化官方或受控人工赛果事实';
COMMENT ON COLUMN match_result_facts.result_source IS 'Result provenance: OFFICIAL or MANUAL / 赛果来源：官方或人工补录';
COMMENT ON COLUMN match_result_facts.source_note IS 'Manual evidence description, never an official claim / 人工证据说明，不得伪装为官方来源';
COMMENT ON COLUMN match_result_facts.entry_reason IS 'Reason for controlled manual entry / 受控人工补录原因';
COMMENT ON COLUMN match_result_facts.entered_by IS 'Authenticated manual entry operator / 已认证的人工补录操作者';
COMMENT ON FUNCTION validate_match_result_fact_payload() IS 'Require result provenance to match official or manual raw payload / 校验赛果来源与官方或人工原始载荷一致';
