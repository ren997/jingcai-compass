-- V29 行为验证：在单事务里跑完再回滚。断言用嵌套 BEGIN/EXCEPTION，避免一条失败就中断整段。
BEGIN;

CREATE TEMP TABLE verify_result (n serial, name text, ok boolean, detail text) ON COMMIT DROP;

-- 夹具：一个 QUEUED run、一条 SUCCESS 归档、一条合法 case、一条 PENDING case。
INSERT INTO backtest_runs (run_key, run_name, run_status, run_stage, operator_username,
    date_from, date_to, parameter_sets, provider_code, model_version, selector_version,
    aggregation_version, builder_version, total_match_count)
VALUES (repeat('a', 32), 'verify', 'QUEUED', 'NONE', 'tester',
    DATE '2026-06-20', DATE '2026-06-21', '[{"groupKey":"g1"}]'::jsonb, 'NOVA_API',
    'v6', 'sel-v1', 'agg-v1', 'slice-v1', 2);

INSERT INTO odds_history_archive (provider_code, external_match_id, match_id, fetch_status,
    payload, payload_hash, bookmaker_count, point_count, series_start_at, series_end_at)
SELECT 'NOVA_API', '2042155', id, 'SUCCESS', '{"providers":[]}'::jsonb, repeat('b', 64), 3, 40,
    TIMESTAMPTZ '2026-06-20 10:00:00+08', TIMESTAMPTZ '2026-06-21 18:00:00+08'
FROM matches WHERE external_match_id = '2042155';

-- 1) 归档只追加
DO $$
BEGIN
    BEGIN
        UPDATE odds_history_archive SET failure_message = 'x' WHERE fetch_status = 'SUCCESS';
        INSERT INTO verify_result (name, ok, detail) VALUES ('archive_append_only', false, 'UPDATE 竟然成功');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('archive_append_only', true, SQLERRM);
    END;
END $$;

-- 2) SUCCESS 缺载荷 → 拒绝
DO $$
BEGIN
    BEGIN
        INSERT INTO odds_history_archive (provider_code, external_match_id, fetch_status)
        VALUES ('NOVA_API', 'x', 'SUCCESS');
        INSERT INTO verify_result (name, ok, detail) VALUES ('archive_payload_scope', false, '缺载荷竟然通过');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('archive_payload_scope', true, SQLERRM);
    END;
END $$;

-- 3) 决策时刻不早于开赛 → 拒绝（无泄漏第一道）
DO $$
DECLARE r record;
BEGIN
    SELECT m.id AS match_id, m.kickoff_time, (SELECT max(id) FROM backtest_runs) AS run_id,
           (SELECT max(id) FROM odds_history_archive) AS archive_id INTO r
    FROM matches m WHERE m.external_match_id = '2042155';
    BEGIN
        INSERT INTO backtest_cases (run_id, group_key, external_match_id, match_id, archive_id,
            decision_at, kickoff_time, skip_reason, decision_stage)
        VALUES (r.run_id, 'g1', '2042155', r.match_id, r.archive_id,
            r.kickoff_time, r.kickoff_time, 'KICKOFF_PASSED', 'KICKOFF_PASSED');
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_before_kickoff', false, '同时刻竟然通过');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_before_kickoff', true, SQLERRM);
    END;
END $$;

-- 4) 冻结行采集晚于决策时刻 → 拒绝（无泄漏第二道）
DO $$
DECLARE r record;
BEGIN
    SELECT m.id AS match_id, m.kickoff_time, (SELECT max(id) FROM backtest_runs) AS run_id,
           (SELECT max(id) FROM odds_history_archive) AS archive_id INTO r
    FROM matches m WHERE m.external_match_id = '2042155';
    BEGIN
        INSERT INTO backtest_cases (run_id, group_key, external_match_id, match_id, archive_id,
            decision_at, kickoff_time, skip_reason, decision_stage, frozen_captured_at)
        VALUES (r.run_id, 'g1', '2042155', r.match_id, r.archive_id,
            r.kickoff_time - interval '105 minutes', r.kickoff_time, 'KICKOFF_PASSED', 'KICKOFF_PASSED',
            r.kickoff_time - interval '60 minutes');
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_no_lookahead', false, '未来冻结行竟然通过');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_no_lookahead', true, SQLERRM);
    END;
END $$;

-- 5) 跳过原因与方向互斥
DO $$
DECLARE r record;
BEGIN
    SELECT m.id AS match_id, m.kickoff_time, (SELECT max(id) FROM backtest_runs) AS run_id,
           (SELECT max(id) FROM odds_history_archive) AS archive_id INTO r
    FROM matches m WHERE m.external_match_id = '2042155';
    BEGIN
        INSERT INTO backtest_cases (run_id, group_key, external_match_id, match_id, archive_id,
            decision_at, kickoff_time, skip_reason, decision_stage, handicap_pick, handicap_confidence)
        VALUES (r.run_id, 'g1', '2042155', r.match_id, r.archive_id,
            r.kickoff_time - interval '105 minutes', r.kickoff_time,
            'LOW_CONFIDENCE_ASIAN_MARKETS', 'BOTH_MARKETS_REJECTED', 'HOME_COVER', 'MEDIUM');
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_skip_vs_pick', false, '跳过却带方向竟然通过');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_skip_vs_pick', true, SQLERRM);
    END;
END $$;

-- 6) 有方向但没有冻结行 → 拒绝
DO $$
DECLARE r record;
BEGIN
    SELECT m.id AS match_id, m.kickoff_time, (SELECT max(id) FROM backtest_runs) AS run_id,
           (SELECT max(id) FROM odds_history_archive) AS archive_id INTO r
    FROM matches m WHERE m.external_match_id = '2042155';
    BEGIN
        INSERT INTO backtest_cases (run_id, group_key, external_match_id, match_id, archive_id,
            decision_at, kickoff_time, decision_stage, totals_pick, totals_confidence)
        VALUES (r.run_id, 'g1', '2042155', r.match_id, r.archive_id,
            r.kickoff_time - interval '105 minutes', r.kickoff_time, 'DECIDED', 'OVER', 'MEDIUM');
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_decided_needs_frozen', false, '无冻结行竟然通过');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_decided_needs_frozen', true, SQLERRM);
    END;
END $$;

-- 合法 case（供后续断言使用）
INSERT INTO backtest_cases (run_id, group_key, external_match_id, match_id, archive_id,
    decision_at, kickoff_time, decision_stage,
    frozen_bookmaker_code, frozen_handicap_line, frozen_home_odds, frozen_away_odds,
    frozen_total_line, frozen_over_odds, frozen_under_odds, frozen_captured_at,
    handicap_pick, handicap_confidence)
SELECT (SELECT max(id) FROM backtest_runs), 'g1', '2042155', m.id,
    (SELECT max(id) FROM odds_history_archive),
    m.kickoff_time - interval '105 minutes', m.kickoff_time, 'DECIDED',
    'pinnacle', '-0.50', 1.9000, 2.0000, '2.75', 1.8800, 1.9200,
    m.kickoff_time - interval '120 minutes', 'HOME_COVER', 'MEDIUM'
FROM matches m WHERE m.external_match_id = '2042155';

-- 7) 决策列不可改
DO $$
BEGIN
    BEGIN
        UPDATE backtest_cases SET handicap_pick = 'AWAY_COVER';
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_decision_immutable', false, '改决策列竟然成功');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('cases_decision_immutable', true, SQLERRM);
    END;
END $$;

-- 8) 未结算完不允许标 SUCCEEDED（必须先走到 AGGREGATING，否则会先被状态机拦住）
DO $$
BEGIN
    UPDATE backtest_runs SET run_status = 'ARCHIVING' WHERE run_status = 'QUEUED';
    UPDATE backtest_runs SET run_status = 'EVALUATING', run_stage = 'EVALUATING' WHERE run_status = 'ARCHIVING';
    UPDATE backtest_runs SET run_status = 'SETTLING', run_stage = 'SETTLING' WHERE run_status = 'EVALUATING';
    UPDATE backtest_runs SET run_status = 'AGGREGATING', run_stage = 'AGGREGATING' WHERE run_status = 'SETTLING';
    BEGIN
        UPDATE backtest_runs SET run_status = 'SUCCEEDED', finished_at = now()
        WHERE run_status = 'AGGREGATING';
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_need_settled', false, '有 PENDING 行竟然能完成');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_need_settled', true, SQLERRM);
    END;
    -- 补结算后再试：应当通过（同时验证 ck_backtest_runs_settled 不是永久死锁）
    UPDATE backtest_cases SET result_status = 'VOID', handicap_status = 'VOID', handicap_profit = 0,
        totals_status = 'VOID', totals_profit = 0, settled_at = now()
    WHERE result_status = 'PENDING';
    BEGIN
        UPDATE backtest_runs SET run_status = 'SUCCEEDED', finished_at = now()
        WHERE run_status = 'AGGREGATING';
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_finish_after_settle', true, '结算完成后可完成');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_finish_after_settle', false, SQLERRM);
    END;
END $$;

-- 9) 定义列不可改
DO $$
BEGIN
    BEGIN
        UPDATE backtest_runs SET date_from = DATE '2026-06-19' WHERE run_status = 'SUCCEEDED';
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_definition_immutable', false, '改定义竟然成功');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_definition_immutable', true, SQLERRM);
    END;
END $$;

-- 10) 终态后任何修改都被拒
DO $$
BEGIN
    BEGIN
        UPDATE backtest_runs SET run_status = 'QUEUED', finished_at = NULL WHERE run_status = 'SUCCEEDED';
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_terminal_immutable', false, '终态竟然能被改回 QUEUED');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('runs_terminal_immutable', true, SQLERRM);
    END;
END $$;

-- 12) 派生指标禁 UPDATE
INSERT INTO backtest_metrics (run_id, group_key, market_scope, case_count, decided_count, skipped_count,
    settled_count, hit_count)
VALUES ((SELECT max(id) FROM backtest_runs), 'g1', 'ALL', 1, 1, 0, 0, 0);
DO $$
BEGIN
    BEGIN
        UPDATE backtest_metrics SET hit_count = 99;
        INSERT INTO verify_result (name, ok, detail) VALUES ('metrics_no_update', false, '改派生指标竟然成功');
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO verify_result (name, ok, detail) VALUES ('metrics_no_update', true, SQLERRM);
    END;
END $$;

SELECT n, name, ok, detail FROM verify_result ORDER BY n;

ROLLBACK;
