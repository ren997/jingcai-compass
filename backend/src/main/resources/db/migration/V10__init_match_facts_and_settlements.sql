-- Versioned official match facts and settlement history / 版本化官方赛果事实与结算历史

DO $$
DECLARE
    legacy_score_count BIGINT;
BEGIN
    SELECT COUNT(*)
    INTO legacy_score_count
    FROM matches
    WHERE home_score IS NOT NULL
       OR away_score IS NOT NULL;

    IF legacy_score_count > 0 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_matches_legacy_scores_unverified',
            MESSAGE = format(
                'V10 refused: %s matches contain legacy scores without versioned official result provenance',
                legacy_score_count
            );
    END IF;
END;
$$;

CREATE TABLE match_result_facts (
    id                        BIGSERIAL PRIMARY KEY,
    match_id                  BIGINT       NOT NULL,
    fact_version              INTEGER      NOT NULL,
    supersedes_fact_version   INTEGER,
    fact_status               VARCHAR(32)  NOT NULL,
    match_status              VARCHAR(32)  NOT NULL,
    home_score                INTEGER,
    away_score                INTEGER,
    raw_data_payload_id       BIGINT       NOT NULL,
    provider_updated_at       TIMESTAMPTZ  NOT NULL,
    is_current                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_match_result_facts_match_version UNIQUE (match_id, fact_version),
    CONSTRAINT fk_match_result_facts_match FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT fk_match_result_facts_raw_payload FOREIGN KEY (raw_data_payload_id) REFERENCES raw_data_payloads (id),
    CONSTRAINT fk_match_result_facts_supersedes FOREIGN KEY (
        match_id,
        supersedes_fact_version
    ) REFERENCES match_result_facts (match_id, fact_version),
    CONSTRAINT ck_match_result_facts_version_chain CHECK (
        (fact_version = 1 AND supersedes_fact_version IS NULL)
        OR (
            fact_version > 1
            AND supersedes_fact_version = fact_version - 1
        )
    ),
    CONSTRAINT ck_match_result_facts_status CHECK (
        fact_status IN ('PENDING', 'FINAL', 'VOID')
    ),
    CONSTRAINT ck_match_result_facts_match_status CHECK (
        match_status IN (
            'SCHEDULED', 'LOCKED', 'IN_PROGRESS', 'FINISHED',
            'POSTPONED', 'CANCELLED', 'ABANDONED'
        )
    ),
    CONSTRAINT ck_match_result_facts_scores_non_negative CHECK (
        (home_score IS NULL OR home_score >= 0)
        AND (away_score IS NULL OR away_score >= 0)
    ),
    CONSTRAINT ck_match_result_facts_score_semantics CHECK (
        (
            fact_status = 'FINAL'
            AND home_score IS NOT NULL
            AND away_score IS NOT NULL
        )
        OR (
            fact_status IN ('PENDING', 'VOID')
            AND home_score IS NULL
            AND away_score IS NULL
        )
    )
);

CREATE UNIQUE INDEX uk_match_result_facts_current
    ON match_result_facts (match_id)
    WHERE is_current;

CREATE OR REPLACE FUNCTION validate_match_result_fact_payload()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    payload_data_type VARCHAR(64);
BEGIN
    SELECT data_type INTO payload_data_type
    FROM raw_data_payloads
    WHERE id = NEW.raw_data_payload_id;

    IF payload_data_type IS DISTINCT FROM 'SPORTTERY_RESULT' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_match_result_facts_sporttery_result_payload',
            MESSAGE = 'match result facts require a SPORTTERY_RESULT raw payload';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_match_result_facts_validate_payload
    BEFORE INSERT ON match_result_facts
    FOR EACH ROW
    EXECUTE FUNCTION validate_match_result_fact_payload();

CREATE TABLE settlements (
    id                              BIGSERIAL PRIMARY KEY,
    prediction_id                   BIGINT       NOT NULL,
    market_type                     VARCHAR(32)  NOT NULL,
    settlement_version              INTEGER      NOT NULL,
    supersedes_settlement_version   INTEGER,
    settlement_status               VARCHAR(32)  NOT NULL,
    match_fact_id                   BIGINT       NOT NULL,
    rule_version                    VARCHAR(64)  NOT NULL,
    is_current                      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_settlements_prediction_market_version UNIQUE (
        prediction_id,
        market_type,
        settlement_version
    ),
    CONSTRAINT fk_settlements_prediction FOREIGN KEY (prediction_id) REFERENCES predictions (id),
    CONSTRAINT fk_settlements_match_fact FOREIGN KEY (match_fact_id) REFERENCES match_result_facts (id),
    CONSTRAINT fk_settlements_supersedes FOREIGN KEY (
        prediction_id,
        market_type,
        supersedes_settlement_version
    ) REFERENCES settlements (
        prediction_id,
        market_type,
        settlement_version
    ),
    CONSTRAINT ck_settlements_market_type CHECK (
        market_type IN ('HAD', 'HHAD')
    ),
    CONSTRAINT ck_settlements_status CHECK (
        settlement_status IN ('HIT', 'MISS', 'VOID')
    ),
    CONSTRAINT ck_settlements_version_chain CHECK (
        (settlement_version = 1 AND supersedes_settlement_version IS NULL)
        OR (
            settlement_version > 1
            AND supersedes_settlement_version = settlement_version - 1
        )
    ),
    CONSTRAINT ck_settlements_rule_version_non_blank CHECK (BTRIM(rule_version) <> '')
);

CREATE UNIQUE INDEX uk_settlements_current
    ON settlements (prediction_id, market_type)
    WHERE is_current;

CREATE OR REPLACE FUNCTION validate_settlement_match_fact()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    prediction_match_id BIGINT;
    fact_match_id       BIGINT;
BEGIN
    SELECT match_id INTO prediction_match_id
    FROM predictions
    WHERE id = NEW.prediction_id;

    SELECT match_id INTO fact_match_id
    FROM match_result_facts
    WHERE id = NEW.match_fact_id;

    IF prediction_match_id IS NULL OR fact_match_id IS NULL OR prediction_match_id <> fact_match_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_settlements_prediction_fact_match',
            MESSAGE = 'settlement prediction and match fact must belong to the same match';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_settlements_validate_match_fact
    BEFORE INSERT ON settlements
    FOR EACH ROW
    EXECUTE FUNCTION validate_settlement_match_fact();

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

CREATE TRIGGER trg_match_result_facts_protect
    BEFORE UPDATE OR DELETE ON match_result_facts
    FOR EACH ROW
    EXECUTE FUNCTION protect_match_result_fact();

CREATE OR REPLACE FUNCTION protect_settlement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_settlements_append_only',
            MESSAGE = 'settlements cannot be deleted';
    END IF;

    IF OLD.is_current = TRUE
        AND NEW.is_current = FALSE
        AND ROW(
            NEW.prediction_id,
            NEW.market_type,
            NEW.settlement_version,
            NEW.supersedes_settlement_version,
            NEW.settlement_status,
            NEW.match_fact_id,
            NEW.rule_version,
            NEW.created_at
        ) IS NOT DISTINCT FROM ROW(
            OLD.prediction_id,
            OLD.market_type,
            OLD.settlement_version,
            OLD.supersedes_settlement_version,
            OLD.settlement_status,
            OLD.match_fact_id,
            OLD.rule_version,
            OLD.created_at
        )
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION USING
        ERRCODE = '23514',
        CONSTRAINT = 'ck_settlements_append_only',
        MESSAGE = 'settlement content is immutable; only current marker can transition true to false';
END;
$$;

CREATE TRIGGER trg_settlements_protect
    BEFORE UPDATE OR DELETE ON settlements
    FOR EACH ROW
    EXECUTE FUNCTION protect_settlement();

COMMENT ON TABLE match_result_facts IS 'Versioned authoritative official match results / 版本化官方权威赛果事实';
COMMENT ON COLUMN match_result_facts.fact_version IS 'Monotonic fact version within one match / 同场比赛递增事实版本';
COMMENT ON COLUMN match_result_facts.supersedes_fact_version IS 'Immediately replaced fact version / 直接被替代的事实版本';
COMMENT ON COLUMN match_result_facts.fact_status IS 'Settlement eligibility: PENDING, FINAL or VOID / 结算资格状态';
COMMENT ON COLUMN match_result_facts.raw_data_payload_id IS 'Authoritative SPORTTERY_RESULT raw payload / 权威体彩赛果原始载荷';
COMMENT ON COLUMN match_result_facts.is_current IS 'Current fact marker, only true to false is allowed / 当前事实标记，仅允许 true 降为 false';

COMMENT ON TABLE settlements IS 'Versioned automatic settlement results / 版本化自动结算结果';
COMMENT ON COLUMN settlements.market_type IS 'Sporttery market: HAD or HHAD / 体彩市场：胜平负或让球胜平负';
COMMENT ON COLUMN settlements.supersedes_settlement_version IS 'Immediately replaced settlement version / 直接被替代的结算版本';
COMMENT ON COLUMN settlements.match_fact_id IS 'Authoritative input match fact / 权威输入赛果事实';
COMMENT ON COLUMN settlements.rule_version IS 'Deterministic calculator rule version / 确定性结算规则版本';
COMMENT ON COLUMN settlements.is_current IS 'Current settlement marker, only true to false is allowed / 当前结算标记，仅允许 true 降为 false';
COMMENT ON FUNCTION validate_settlement_match_fact() IS 'Require settlement prediction and fact to reference the same match / 校验结算预测与赛果属于同场比赛';
COMMENT ON FUNCTION validate_match_result_fact_payload() IS 'Require SPORTTERY_RESULT source payload for a match fact / 要求赛果事实引用 SPORTTERY_RESULT 原始载荷';
COMMENT ON FUNCTION protect_match_result_fact() IS 'Prevent mutation or deletion of historical match facts / 禁止修改或删除历史赛果事实';
COMMENT ON FUNCTION protect_settlement() IS 'Prevent mutation or deletion of historical settlements / 禁止修改或删除历史结算';
