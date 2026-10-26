-- Historical backtest storage / 历史回测存储
--
-- 四张表，各自独立成表而不是复用既有权表，理由逐条写在下面每段的开头。
-- 本迁移刻意不含任何对 settlements / predictions 的写法：
-- 回测结论绝不允许流进线上统计口径（见 ck_backtest_* 与 BacktestMigrationTest 的静态断言）。

-- ---------------------------------------------------------------------------
-- 1) odds_history_archive：外部事实，只追加
-- ---------------------------------------------------------------------------
-- 不复用 raw_data_payloads：那张表语义是「一次同步调用的原始响应」，按 run 计、存当前态、
-- 且 data_type 有 CHECK 白名单。归档的语义是「一场一序列全史 + 按决策时刻切好的截面」，
-- 按 match_id 计，需要终态判定与增量补采状态机。混用会让两种生命周期互相污染。
--
-- 只追加（触发器禁 UPDATE/DELETE）是为了让「这次回测基于哪一份原始序列」永远可审计：
-- 上游赔率会被改写，一旦允许就地更新，历史结论就再也无法复现。
CREATE TABLE odds_history_archive (
    id                  BIGSERIAL PRIMARY KEY,
    provider_code       VARCHAR(64)  NOT NULL,
    external_match_id   VARCHAR(64)  NOT NULL,
    match_id            BIGINT,
    lottery_date        DATE,
    league_name         VARCHAR(128),
    home_team_name      VARCHAR(128),
    away_team_name      VARCHAR(128),
    kickoff_time        TIMESTAMPTZ,
    fetch_status        VARCHAR(24)  NOT NULL,
    http_status         INTEGER,
    upstream_code       INTEGER,
    payload             JSONB,
    payload_hash        CHAR(64),
    bookmaker_count     INTEGER,
    point_count         INTEGER,
    series_start_at     TIMESTAMPTZ,
    series_end_at       TIMESTAMPTZ,
    trimmed             BOOLEAN      NOT NULL DEFAULT FALSE,
    failure_message     TEXT,
    attempted_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_odds_history_archive_match
        FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT ck_odds_history_archive_status CHECK (
        fetch_status IN ('SUCCESS', 'NO_HISTORY', 'NO_QUOTE_YET', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')
    ),
    -- 防「看着有数据其实没解析」：SUCCESS 必须带齐载荷与序列两端，
    -- 非 SUCCESS 必须一个字段都不许留（否则重试判定会误以为已经采到）。
    CONSTRAINT ck_odds_history_archive_payload_scope CHECK (
        (
            fetch_status = 'SUCCESS'
            AND payload IS NOT NULL
            AND payload_hash IS NOT NULL
            AND bookmaker_count > 0
            AND point_count > 0
            AND series_start_at IS NOT NULL
            AND series_end_at IS NOT NULL
        )
        OR (
            fetch_status <> 'SUCCESS'
            AND payload IS NULL
            AND payload_hash IS NULL
            AND bookmaker_count IS NULL
            AND point_count IS NULL
            AND series_start_at IS NULL
            AND series_end_at IS NULL
        )
    ),
    CONSTRAINT ck_odds_history_archive_payload_hash CHECK (
        payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_odds_history_archive_series CHECK (
        series_start_at IS NULL OR series_end_at IS NULL OR series_start_at <= series_end_at
    ),
    CONSTRAINT ck_odds_history_archive_counts CHECK (
        (bookmaker_count IS NULL OR bookmaker_count >= 0)
        AND (point_count IS NULL OR point_count >= 0)
    )
);

-- 一场一序列：重复补采走 ON CONFLICT DO NOTHING，失败结论也能被复用（失败行不受此索引约束）。
CREATE UNIQUE INDEX uk_odds_history_archive_success
    ON odds_history_archive (provider_code, external_match_id)
    WHERE fetch_status = 'SUCCESS';

CREATE INDEX idx_odds_history_archive_match
    ON odds_history_archive (provider_code, external_match_id, attempted_at DESC);

CREATE INDEX idx_odds_history_archive_date_league
    ON odds_history_archive (lottery_date, league_name);

COMMENT ON TABLE odds_history_archive IS
    'NovaAPI odds-history raw series archive, append only / 历史赔率原始序列归档，只追加';
COMMENT ON COLUMN odds_history_archive.fetch_status IS
    'SUCCESS / NO_HISTORY(404) / NO_QUOTE_YET(空点) / RETRYABLE_FAILURE / PERMANENT_FAILURE';
COMMENT ON COLUMN odds_history_archive.trimmed IS
    'Payload trimmed to marketId 4 and 5 / 载荷是否已裁剪到让球与大小球两个市场';
COMMENT ON COLUMN odds_history_archive.series_start_at IS
    'First point time of the series, not the fetch time / 序列首点时刻，不是采集时刻';

-- ---------------------------------------------------------------------------
-- 2) backtest_runs：定义 + 进度 + 版本锚点
-- ---------------------------------------------------------------------------
-- 参数组是本次运行的输入快照、没有独立生命周期，因此内嵌 JSONB 而不单独建表：
-- 单独建表会让人误以为参数组可以跨 run 复用，而「同一组阈值换一批场次」恰恰是不可比的。
CREATE TABLE backtest_runs (
    id                      BIGSERIAL PRIMARY KEY,
    run_key                 CHAR(32)     NOT NULL,
    run_name                VARCHAR(128) NOT NULL,
    run_status              VARCHAR(24)  NOT NULL,
    run_stage               VARCHAR(24)  NOT NULL,
    operator_username       VARCHAR(64)  NOT NULL,
    prediction_type         VARCHAR(32)  NOT NULL DEFAULT 'ASIAN',
    date_from               DATE         NOT NULL,
    date_to                 DATE         NOT NULL,
    league_names            JSONB,
    decision_lead_minutes   INTEGER      NOT NULL DEFAULT 105,
    parameter_sets          JSONB        NOT NULL,
    provider_code           VARCHAR(64)  NOT NULL,
    git_sha                 VARCHAR(64),
    model_version           VARCHAR(64)  NOT NULL,
    selector_version        VARCHAR(64)  NOT NULL,
    aggregation_version     VARCHAR(64)  NOT NULL,
    builder_version         VARCHAR(64)  NOT NULL,
    total_match_count       INTEGER      NOT NULL DEFAULT 0,
    processed_match_count   INTEGER      NOT NULL DEFAULT 0,
    archive_reused_count    INTEGER      NOT NULL DEFAULT 0,
    archive_fetched_count   INTEGER      NOT NULL DEFAULT 0,
    archive_failed_count    INTEGER      NOT NULL DEFAULT 0,
    case_count              INTEGER      NOT NULL DEFAULT 0,
    estimate_quota_cost     INTEGER,
    quota_cost              INTEGER      NOT NULL DEFAULT 0,
    cancel_requested        BOOLEAN      NOT NULL DEFAULT FALSE,
    resume_count            INTEGER      NOT NULL DEFAULT 0,
    last_heartbeat_at       TIMESTAMPTZ,
    error_code              VARCHAR(64),
    error_message           TEXT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_backtest_runs_key UNIQUE (run_key),
    CONSTRAINT ck_backtest_runs_status CHECK (
        run_status IN ('QUEUED', 'ARCHIVING', 'EVALUATING', 'SETTLING', 'AGGREGATING',
                       'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED')
    ),
    CONSTRAINT ck_backtest_runs_stage CHECK (
        run_stage IN ('NONE', 'ARCHIVING', 'EVALUATING', 'SETTLING', 'AGGREGATING', 'DONE')
    ),
    CONSTRAINT ck_backtest_runs_prediction_type CHECK (prediction_type = 'ASIAN'),
    -- odds-history 只回溯约 4 个月，跨度上限 120 天是「额度可解释」的兜底：
    -- 2400 场 × 2s 限速 ≈ 80 分钟纯等待，再长就该拆成多个 run 而不是一个巨型 run。
    CONSTRAINT ck_backtest_runs_range CHECK (
        date_from <= date_to AND date_to - date_from <= 120
    ),
    CONSTRAINT ck_backtest_runs_lead CHECK (decision_lead_minutes BETWEEN 1 AND 2880),
    CONSTRAINT ck_backtest_runs_groups CHECK (
        jsonb_typeof(parameter_sets) = 'array'
        AND jsonb_array_length(parameter_sets) BETWEEN 1 AND 8
    ),
    CONSTRAINT ck_backtest_runs_league_names CHECK (
        league_names IS NULL OR jsonb_typeof(league_names) = 'array'
    ),
    CONSTRAINT ck_backtest_runs_counts CHECK (
        archive_reused_count >= 0
        AND archive_fetched_count >= 0
        AND archive_failed_count >= 0
        AND archive_reused_count + archive_fetched_count + archive_failed_count = processed_match_count
        AND processed_match_count <= total_match_count
        AND case_count >= 0
        AND quota_cost >= 0
        AND resume_count >= 0
    ),
    -- 终态与结束时刻同生同灭：只看状态会漏掉「标了 SUCCEEDED 但没写 finished_at」的半截行。
    CONSTRAINT ck_backtest_runs_terminal CHECK (
        (
            run_status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        ) = (finished_at IS NOT NULL)
    )
);

CREATE INDEX idx_backtest_runs_status ON backtest_runs (run_status, created_at DESC);
CREATE INDEX idx_backtest_runs_created ON backtest_runs (created_at DESC);

COMMENT ON TABLE backtest_runs IS
    'One backtest run: definition, progress and version anchors / 一次回测：定义、进度与版本锚点';
COMMENT ON COLUMN backtest_runs.parameter_sets IS
    'Array of threshold parameter sets, 1..8 / 阈值参数组数组，1~8 组';
COMMENT ON COLUMN backtest_runs.decision_lead_minutes IS
    'Minutes before kickoff used as decision instant; recorded only, not a comparison axis / 决策截面距开赛分钟数，仅记录不参与对比';
COMMENT ON COLUMN backtest_runs.git_sha IS
    'Code revision at run time, for attributing conclusions to a build / 运行时代码版本，用于把结论归因到某次构建';

-- ---------------------------------------------------------------------------
-- 3) backtest_cases：事实宽表（唯一被筛选/对比/聚合的实体）
-- ---------------------------------------------------------------------------
-- 粒度 = 场次 × 参数组 × 决策时点。group_key 进唯一键保证断点续跑幂等，
-- decision_at 进唯一键保证「一个 run 内多截面」将来不用改主键。
CREATE TABLE backtest_cases (
    id                      BIGSERIAL PRIMARY KEY,
    run_id                  BIGINT       NOT NULL,
    group_key               CHAR(16)     NOT NULL,
    external_match_id       VARCHAR(64)  NOT NULL,
    match_id                BIGINT       NOT NULL,
    archive_id              BIGINT       NOT NULL,
    lottery_match_no        VARCHAR(32),
    league_name             VARCHAR(128),
    decision_at             TIMESTAMPTZ  NOT NULL,
    kickoff_time            TIMESTAMPTZ  NOT NULL,
    skip_reason             VARCHAR(48),
    decision_stage          VARCHAR(32)  NOT NULL,
    frozen_bookmaker_code   VARCHAR(64),
    frozen_handicap_line    NUMERIC(6,2),
    frozen_home_odds        NUMERIC(10,4),
    frozen_away_odds        NUMERIC(10,4),
    frozen_total_line       NUMERIC(6,2),
    frozen_over_odds        NUMERIC(10,4),
    frozen_under_odds       NUMERIC(10,4),
    frozen_captured_at      TIMESTAMPTZ,
    handicap_pick           VARCHAR(32),
    handicap_confidence     VARCHAR(16),
    totals_pick             VARCHAR(32),
    totals_confidence       VARCHAR(16),
    handicap_dispersion     NUMERIC(9,6),
    totals_dispersion       NUMERIC(9,6),
    lambda_home             NUMERIC(9,6),
    lambda_away             NUMERIC(9,6),
    model_cover_prob        NUMERIC(9,6),
    market_cover_prob       NUMERIC(9,6),
    edge_cover              NUMERIC(9,6),
    model_over_prob         NUMERIC(9,6),
    market_over_prob        NUMERIC(9,6),
    edge_over               NUMERIC(9,6),
    neighbor_max_deviation  NUMERIC(9,6),
    -- 残差量级在 1e-16，NUMERIC 定标只会把它压成 0，因此用双精度。
    max_residual            DOUBLE PRECISION,
    handicap_gate_pass      BOOLEAN,
    totals_gate_pass        BOOLEAN,
    result_status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    home_score              INTEGER,
    away_score              INTEGER,
    handicap_status         VARCHAR(16),
    handicap_profit         NUMERIC(10,4),
    totals_status           VARCHAR(16),
    totals_profit           NUMERIC(10,4),
    settled_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_backtest_cases_run FOREIGN KEY (run_id) REFERENCES backtest_runs (id),
    CONSTRAINT fk_backtest_cases_match FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT fk_backtest_cases_archive FOREIGN KEY (archive_id) REFERENCES odds_history_archive (id),
    CONSTRAINT uk_backtest_cases_identity UNIQUE (run_id, external_match_id, group_key, decision_at),
    -- 无泄漏第一道：决策时刻必须严格早于开赛。
    CONSTRAINT ck_backtest_cases_before_kickoff CHECK (decision_at < kickoff_time),
    -- 无泄漏第二道：冻结行的采集时刻不得晚于决策时刻。
    CONSTRAINT ck_backtest_cases_no_lookahead CHECK (
        frozen_captured_at IS NULL OR frozen_captured_at <= decision_at
    ),
    CONSTRAINT ck_backtest_cases_skip_reason CHECK (
        skip_reason IS NULL OR skip_reason IN (
            'NOT_SCHEDULED', 'KICKOFF_PASSED', 'MISSING_CONFIRMED_ASIAN_MARKET',
            'INVALID_ASIAN_MARKET', 'INSUFFICIENT_ASIAN_BOOKMAKERS',
            'INSUFFICIENT_ASIAN_CONSTRAINTS', 'INCONSISTENT_ASIAN_MARKETS',
            'NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE', 'NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE',
            'MARKET_DISPERSION_TOO_HIGH', 'LOW_CONFIDENCE_ASIAN_MARKETS'
        )
    ),
    CONSTRAINT ck_backtest_cases_stage CHECK (
        decision_stage IN (
            'NOT_SCHEDULED', 'KICKOFF_PASSED', 'MISSING_ROWS', 'INVALID_ROWS',
            'INSUFFICIENT_HANDICAP', 'NO_TOTALS_CONSTRAINT', 'INFEASIBLE', 'INCONSISTENT',
            'NO_FROZEN_ROW', 'FROZEN_TOTALS_LINE_MISMATCH', 'BOTH_MARKETS_REJECTED', 'DECIDED'
        )
    ),
    CONSTRAINT ck_backtest_cases_handicap_pick CHECK (
        handicap_pick IS NULL OR handicap_pick IN ('HOME_COVER', 'AWAY_COVER')
    ),
    CONSTRAINT ck_backtest_cases_totals_pick CHECK (
        totals_pick IS NULL OR totals_pick IN ('OVER', 'UNDER')
    ),
    CONSTRAINT ck_backtest_cases_confidence CHECK (
        (handicap_confidence IS NULL OR handicap_confidence IN ('HIGH', 'MEDIUM', 'LOW'))
        AND (totals_confidence IS NULL OR totals_confidence IN ('HIGH', 'MEDIUM', 'LOW'))
    ),
    -- 有方向才有置信度；生产落库口径是 pick 为空则 confidence 必空。
    CONSTRAINT ck_backtest_cases_pick_confidence CHECK (
        (handicap_pick IS NULL) = (handicap_confidence IS NULL)
        AND (totals_pick IS NULL) = (totals_confidence IS NULL)
    ),
    -- 跳过的场次不可能带方向；判定过的场次必须有冻结行且至少一个方向。
    CONSTRAINT ck_backtest_cases_decided CHECK (
        (
            skip_reason IS NOT NULL
            AND handicap_pick IS NULL
            AND totals_pick IS NULL
        )
        OR (
            skip_reason IS NULL
            AND frozen_bookmaker_code IS NOT NULL
            AND frozen_handicap_line IS NOT NULL
            AND frozen_home_odds IS NOT NULL
            AND frozen_away_odds IS NOT NULL
            AND frozen_total_line IS NOT NULL
            AND frozen_over_odds IS NOT NULL
            AND frozen_under_odds IS NOT NULL
            AND frozen_captured_at IS NOT NULL
            AND (handicap_pick IS NOT NULL OR totals_pick IS NOT NULL)
        )
    ),
    CONSTRAINT ck_backtest_cases_result CHECK (
        (result_status = 'PENDING'
            AND home_score IS NULL AND away_score IS NULL
            AND handicap_status IS NULL AND handicap_profit IS NULL
            AND totals_status IS NULL AND totals_profit IS NULL
            AND settled_at IS NULL)
        OR (result_status = 'VOID'
            AND handicap_status = 'VOID' AND totals_status = 'VOID'
            AND handicap_profit = 0 AND totals_profit = 0
            AND settled_at IS NOT NULL)
        OR (result_status = 'FINAL'
            AND home_score IS NOT NULL AND away_score IS NOT NULL
            AND settled_at IS NOT NULL)
    ),
    CONSTRAINT ck_backtest_cases_profit_scope CHECK (
        (handicap_status IS NULL) = (handicap_profit IS NULL)
        AND (totals_status IS NULL) = (totals_profit IS NULL)
        AND (handicap_status IS NULL OR handicap_status IN
             ('HIT', 'MISS', 'PUSH', 'HALF_WIN', 'HALF_LOSS', 'VOID'))
        AND (totals_status IS NULL OR totals_status IN
             ('HIT', 'MISS', 'PUSH', 'HALF_WIN', 'HALF_LOSS', 'VOID'))
    ),
    CONSTRAINT ck_backtest_cases_prob_range CHECK (
        (handicap_dispersion IS NULL OR handicap_dispersion BETWEEN 0 AND 1)
        AND (totals_dispersion IS NULL OR totals_dispersion BETWEEN 0 AND 1)
        AND (model_cover_prob IS NULL OR model_cover_prob BETWEEN 0 AND 1)
        AND (market_cover_prob IS NULL OR market_cover_prob BETWEEN 0 AND 1)
        AND (model_over_prob IS NULL OR model_over_prob BETWEEN 0 AND 1)
        AND (market_over_prob IS NULL OR market_over_prob BETWEEN 0 AND 1)
        AND (neighbor_max_deviation IS NULL OR neighbor_max_deviation BETWEEN 0 AND 1)
        AND (home_score IS NULL OR home_score >= 0)
        AND (away_score IS NULL OR away_score >= 0)
    )
);

CREATE INDEX idx_backtest_cases_group ON backtest_cases (run_id, group_key);
CREATE INDEX idx_backtest_cases_outcome
    ON backtest_cases (run_id, group_key, handicap_status, totals_status);
CREATE INDEX idx_backtest_cases_skipped
    ON backtest_cases (run_id, group_key) WHERE skip_reason IS NOT NULL;
CREATE INDEX idx_backtest_cases_match ON backtest_cases (run_id, external_match_id);

COMMENT ON TABLE backtest_cases IS
    'One row per match x parameter group x decision instant / 场次 × 参数组 × 决策时点';
COMMENT ON COLUMN backtest_cases.group_key IS
    'Parameter set fingerprint, sha256 prefix / 参数组指纹';
COMMENT ON COLUMN backtest_cases.archive_id IS
    'Archived odds series this case was sliced from / 本行切片所依据的归档序列';
COMMENT ON COLUMN backtest_cases.frozen_captured_at IS
    'Capture time of the frozen row, never later than decision_at / 冻结行采集时刻，不得晚于决策时刻';

-- ---------------------------------------------------------------------------
-- 4) backtest_metrics：派生表，可随时重算
-- ---------------------------------------------------------------------------
-- 派生表不能就地改：一旦允许 UPDATE，「指标是被算出来的」这条就失去了保证，
-- 事后无法区分「算错了」和「被改过」。要改就删了重算（触发器禁 UPDATE/DELETE）。
CREATE TABLE backtest_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    group_key           CHAR(16)     NOT NULL,
    market_scope        VARCHAR(32)  NOT NULL,
    case_count          INTEGER      NOT NULL DEFAULT 0,
    decided_count       INTEGER      NOT NULL DEFAULT 0,
    skipped_count       INTEGER      NOT NULL DEFAULT 0,
    pick_count          INTEGER      NOT NULL DEFAULT 0,
    settled_count       INTEGER      NOT NULL DEFAULT 0,
    push_count          INTEGER      NOT NULL DEFAULT 0,
    half_win_count      INTEGER      NOT NULL DEFAULT 0,
    half_loss_count     INTEGER      NOT NULL DEFAULT 0,
    hit_count           INTEGER      NOT NULL DEFAULT 0,
    miss_count          INTEGER      NOT NULL DEFAULT 0,
    hit_rate            NUMERIC(9,6),
    weighted_hit_rate   NUMERIC(9,6),
    profit_sum          NUMERIC(12,4),
    roi                 NUMERIC(9,6),
    yield_rate          NUMERIC(9,6),
    brier_model         NUMERIC(9,6),
    brier_market        NUMERIC(9,6),
    brier_delta         NUMERIC(9,6),
    brier_sample_size   INTEGER,
    skip_reason_counts  JSONB,
    metrics_json        JSONB,
    computed_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_backtest_metrics_run FOREIGN KEY (run_id) REFERENCES backtest_runs (id),
    CONSTRAINT uk_backtest_metrics_scope UNIQUE (run_id, group_key, market_scope),
    CONSTRAINT ck_backtest_metrics_scope CHECK (
        market_scope IN ('ALL', 'ASIAN_HANDICAP', 'ASIAN_TOTAL_GOALS')
    ),
    CONSTRAINT ck_backtest_metrics_counts CHECK (
        case_count >= 0 AND decided_count >= 0 AND skipped_count >= 0 AND pick_count >= 0
        AND settled_count >= 0 AND push_count >= 0 AND half_win_count >= 0
        AND half_loss_count >= 0 AND hit_count >= 0 AND miss_count >= 0
        AND case_count = decided_count + skipped_count
        AND settled_count = hit_count + miss_count + push_count + half_win_count + half_loss_count
    ),
    CONSTRAINT ck_backtest_metrics_rates CHECK (
        (hit_rate IS NULL OR hit_rate BETWEEN 0 AND 1)
        AND (weighted_hit_rate IS NULL OR weighted_hit_rate BETWEEN 0 AND 1)
        AND (brier_model IS NULL OR brier_model BETWEEN 0 AND 1)
        AND (brier_market IS NULL OR brier_market BETWEEN 0 AND 1)
    )
);

CREATE INDEX idx_backtest_metrics_run ON backtest_metrics (run_id);

COMMENT ON TABLE backtest_metrics IS
    'Derived metrics per parameter group and market scope; recomputable / 参数组 × 市场口径的派生指标，可重算';
COMMENT ON COLUMN backtest_metrics.weighted_hit_rate IS
    'HIT 1 / HALF_WIN .75 / PUSH excluded from denominator / 加权命中率，走盘不进分母';
COMMENT ON COLUMN backtest_metrics.yield_rate IS
    'profit per settled leg, distinct from roi over all legs / 单腿收益率';

-- ---------------------------------------------------------------------------
-- 5) 触发器
-- ---------------------------------------------------------------------------

-- 归档只追加：UPDATE / DELETE 一律拒绝。
CREATE OR REPLACE FUNCTION protect_odds_history_archive()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'odds_history_archive is append only / 归档表只追加，不允许修改或删除'
        USING CONSTRAINT = 'ck_odds_history_archive_append_only';
END;
$$;

CREATE TRIGGER trg_odds_history_archive_append_only
    BEFORE UPDATE OR DELETE ON odds_history_archive
    FOR EACH ROW EXECUTE FUNCTION protect_odds_history_archive();

-- 运行定义不可变 + 状态机 + 「标 SUCCEEDED 前所有行的结算结论必须已落地」。
CREATE OR REPLACE FUNCTION guard_backtest_runs()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    pending_cases INTEGER;
BEGIN
    -- 定义列中途改动会让「同一 run_key 的结论到底基于什么输入」无法回答。
    IF NEW.run_key <> OLD.run_key
        OR NEW.date_from <> OLD.date_from
        OR NEW.date_to <> OLD.date_to
        OR NEW.decision_lead_minutes <> OLD.decision_lead_minutes
        OR NEW.parameter_sets <> OLD.parameter_sets
        OR NEW.prediction_type <> OLD.prediction_type
        OR NEW.provider_code <> OLD.provider_code
        OR NEW.model_version <> OLD.model_version
        OR NEW.selector_version <> OLD.selector_version
        OR NEW.aggregation_version <> OLD.aggregation_version
        OR NEW.builder_version <> OLD.builder_version
        OR NEW.created_at <> OLD.created_at
        OR NEW.operator_username <> OLD.operator_username
    THEN
        RAISE EXCEPTION 'backtest run definition is immutable / 回测定义不可修改'
            USING CONSTRAINT = 'ck_backtest_runs_definition_immutable';
    END IF;

    IF NEW.league_names IS DISTINCT FROM OLD.league_names THEN
        RAISE EXCEPTION 'backtest run definition is immutable / 回测定义不可修改'
            USING CONSTRAINT = 'ck_backtest_runs_definition_immutable';
    END IF;

    -- 终态后连状态都不许动；唯一例外是 INTERRUPTED → QUEUED（续跑）。
    IF OLD.run_status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal backtest run must not change / 终态回测不可再改，需重跑请新建 run'
            USING CONSTRAINT = 'ck_backtest_runs_terminal_immutable';
    END IF;

    IF NEW.run_status <> OLD.run_status AND NOT (
        (OLD.run_status = 'QUEUED' AND NEW.run_status IN
            ('ARCHIVING', 'CANCELLED', 'FAILED', 'INTERRUPTED'))
        OR (OLD.run_status = 'ARCHIVING' AND NEW.run_status IN
            ('EVALUATING', 'FAILED', 'PARTIAL', 'CANCELLED', 'INTERRUPTED'))
        OR (OLD.run_status = 'EVALUATING' AND NEW.run_status IN
            ('SETTLING', 'FAILED', 'PARTIAL', 'CANCELLED', 'INTERRUPTED'))
        OR (OLD.run_status = 'SETTLING' AND NEW.run_status IN
            ('AGGREGATING', 'FAILED', 'PARTIAL', 'CANCELLED', 'INTERRUPTED'))
        OR (OLD.run_status = 'AGGREGATING' AND NEW.run_status IN
            ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED'))
        OR (OLD.run_status = 'INTERRUPTED' AND NEW.run_status IN
            ('QUEUED', 'FAILED', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'illegal backtest run transition % -> % / 非法的回测状态流转',
            OLD.run_status, NEW.run_status
            USING CONSTRAINT = 'ck_backtest_runs_transition';
    END IF;

    -- 续跑必须把 finished_at 清空，否则 ck_backtest_runs_terminal 会把它当成又一次终态。
    IF OLD.run_status = 'INTERRUPTED' AND NEW.run_status = 'QUEUED' AND NEW.finished_at IS NOT NULL THEN
        RAISE EXCEPTION 'resumed run must clear finished_at / 续跑必须清空结束时刻'
            USING CONSTRAINT = 'ck_backtest_runs_resume';
    END IF;

    -- 标 SUCCEEDED 时不允许还有未结算的行：那意味着完成时刻之后的结算不会再被触发。
    IF NEW.run_status = 'SUCCEEDED' THEN
        SELECT count(*) INTO pending_cases
        FROM backtest_cases
        WHERE run_id = NEW.id AND result_status = 'PENDING';
        IF pending_cases > 0 THEN
            RAISE EXCEPTION 'cannot finish run with % pending cases / 仍有未结算场次',
                pending_cases
                USING CONSTRAINT = 'ck_backtest_runs_settled';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_backtest_runs_guard
    BEFORE UPDATE ON backtest_runs
    FOR EACH ROW EXECUTE FUNCTION guard_backtest_runs();

-- 事实宽表：禁 DELETE；决策列不可改；结算只写一次。
CREATE OR REPLACE FUNCTION guard_backtest_cases()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'backtest_cases rows must not be deleted / 回测明细不可删除'
            USING CONSTRAINT = 'ck_backtest_cases_no_delete';
    END IF;

    -- 结算列可以写一次，其余列（决策、诊断、冻结行）一律冻结：
    -- 允许事后改决策列，就等于允许「拿赛果去修饰当初的判断」。
    IF NEW.skip_reason IS DISTINCT FROM OLD.skip_reason
        OR NEW.decision_stage IS DISTINCT FROM OLD.decision_stage
        OR NEW.decision_at IS DISTINCT FROM OLD.decision_at
        OR NEW.kickoff_time IS DISTINCT FROM OLD.kickoff_time
        OR NEW.group_key IS DISTINCT FROM OLD.group_key
        OR NEW.external_match_id IS DISTINCT FROM OLD.external_match_id
        OR NEW.frozen_bookmaker_code IS DISTINCT FROM OLD.frozen_bookmaker_code
        OR NEW.frozen_handicap_line IS DISTINCT FROM OLD.frozen_handicap_line
        OR NEW.frozen_home_odds IS DISTINCT FROM OLD.frozen_home_odds
        OR NEW.frozen_away_odds IS DISTINCT FROM OLD.frozen_away_odds
        OR NEW.frozen_total_line IS DISTINCT FROM OLD.frozen_total_line
        OR NEW.frozen_over_odds IS DISTINCT FROM OLD.frozen_over_odds
        OR NEW.frozen_under_odds IS DISTINCT FROM OLD.frozen_under_odds
        OR NEW.frozen_captured_at IS DISTINCT FROM OLD.frozen_captured_at
        OR NEW.handicap_pick IS DISTINCT FROM OLD.handicap_pick
        OR NEW.handicap_confidence IS DISTINCT FROM OLD.handicap_confidence
        OR NEW.totals_pick IS DISTINCT FROM OLD.totals_pick
        OR NEW.totals_confidence IS DISTINCT FROM OLD.totals_confidence
        OR NEW.handicap_dispersion IS DISTINCT FROM OLD.handicap_dispersion
        OR NEW.totals_dispersion IS DISTINCT FROM OLD.totals_dispersion
        OR NEW.lambda_home IS DISTINCT FROM OLD.lambda_home
        OR NEW.lambda_away IS DISTINCT FROM OLD.lambda_away
        OR NEW.model_cover_prob IS DISTINCT FROM OLD.model_cover_prob
        OR NEW.market_cover_prob IS DISTINCT FROM OLD.market_cover_prob
        OR NEW.edge_cover IS DISTINCT FROM OLD.edge_cover
        OR NEW.model_over_prob IS DISTINCT FROM OLD.model_over_prob
        OR NEW.market_over_prob IS DISTINCT FROM OLD.market_over_prob
        OR NEW.edge_over IS DISTINCT FROM OLD.edge_over
        OR NEW.neighbor_max_deviation IS DISTINCT FROM OLD.neighbor_max_deviation
        OR NEW.max_residual IS DISTINCT FROM OLD.max_residual
        OR NEW.handicap_gate_pass IS DISTINCT FROM OLD.handicap_gate_pass
        OR NEW.totals_gate_pass IS DISTINCT FROM OLD.totals_gate_pass
        OR NEW.archive_id IS DISTINCT FROM OLD.archive_id
    THEN
        RAISE EXCEPTION 'backtest case decision columns are immutable / 回测决策列不可修改'
            USING CONSTRAINT = 'ck_backtest_cases_decision_immutable';
    END IF;

    IF OLD.settled_at IS NOT NULL AND NEW.settled_at IS DISTINCT FROM OLD.settled_at THEN
        RAISE EXCEPTION 'backtest case already settled / 该行已结算，不能重复写入'
            USING CONSTRAINT = 'ck_backtest_cases_settled_once';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_backtest_cases_guard
    BEFORE UPDATE OR DELETE ON backtest_cases
    FOR EACH ROW EXECUTE FUNCTION guard_backtest_cases();

-- 派生表整体禁改：只允许插入与按 run 删除重算。
CREATE OR REPLACE FUNCTION protect_backtest_metrics()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- 重算路径是 DELETE + INSERT，因此只禁 UPDATE。
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'backtest_metrics rows are derived, recompute instead / 派生指标请删除后重算'
        USING CONSTRAINT = 'ck_backtest_metrics_no_update';
END;
$$;

CREATE TRIGGER trg_backtest_metrics_no_update
    BEFORE UPDATE ON backtest_metrics
    FOR EACH ROW EXECUTE FUNCTION protect_backtest_metrics();
