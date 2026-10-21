-- Asian settlement markets, extended statuses and net profit / 亚盘结算市场、扩展状态与净收益

ALTER TABLE settlements
    DROP CONSTRAINT ck_settlements_market_type,
    DROP CONSTRAINT ck_settlements_status,
    ADD COLUMN profit NUMERIC(10,4),
    ADD CONSTRAINT ck_settlements_market_type CHECK (
        market_type IN ('HAD', 'HHAD', 'ASIAN_HANDICAP', 'ASIAN_TOTAL_GOALS')
    ),
    ADD CONSTRAINT ck_settlements_status CHECK (
        settlement_status IN ('HIT', 'MISS', 'VOID', 'PUSH', 'HALF_WIN', 'HALF_LOSS')
    ),
    ADD CONSTRAINT ck_settlements_profit_scope CHECK (
        (
            market_type IN ('HAD', 'HHAD')
            AND profit IS NULL
        )
        OR (
            market_type IN ('ASIAN_HANDICAP', 'ASIAN_TOTAL_GOALS')
            AND profit IS NOT NULL
        )
    );

COMMENT ON COLUMN settlements.market_type IS 'Settlement market: HAD, HHAD, ASIAN_HANDICAP or ASIAN_TOTAL_GOALS / 结算市场：胜平负、让球胜平负、亚盘让球或亚盘大小球';
COMMENT ON COLUMN settlements.settlement_status IS 'Settlement status including asian push and split half results / 结算状态，含亚盘走盘与拆盘半赢半输';
COMMENT ON COLUMN settlements.profit IS
    'Net profit per unit stake, asian markets only / 以单注本金 1 计算的净收益，仅亚盘市场';

-- Profit is settled content, so the append-only guard must cover the new column as well.
-- 净收益属于结算内容，必须与其它结算字段一样受"只允许 current 由 true 降为 false"的保护。
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
            NEW.profit,
            NEW.match_fact_id,
            NEW.rule_version,
            NEW.created_at
        ) IS NOT DISTINCT FROM ROW(
            OLD.prediction_id,
            OLD.market_type,
            OLD.settlement_version,
            OLD.supersedes_settlement_version,
            OLD.settlement_status,
            OLD.profit,
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
