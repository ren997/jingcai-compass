-- NovaAPI single-source prerequisites / NovaAPI 单数据源改造的前置结构
--
-- 本迁移只做「让同一份数据可以不再依赖映射层」所需的四类结构改动，
-- 不删除任何映射表：映射层的物理删除放在交付末尾（阶段 5），
-- 因为在那之前旧链路必须仍可回退。
--
-- 四类改动互相独立，任何一条单独 apply 都不会让现有代码失效。

-- ---------------------------------------------------------------------------
-- 1) matches.external_match_id：供应商侧比赛 ID
-- ---------------------------------------------------------------------------
-- 用可空而不是 NOT NULL：仓库内 160+ 个测试直接 INSERT INTO matches 造数据，
-- NOT NULL 会让它们同时变红，而那个改动本身与单源无关。
-- 部分唯一索引（WHERE ... IS NOT NULL）允许多行 NULL 并存，等价于「有值时唯一」。
-- 单源后它成为 upsert 键；lottery_match_no 仍然保留，因为公开列表排序
-- （MatchMapper 的 LOTTERY_MATCH_NO_ASC/DESC）与前端展示都在用它。
ALTER TABLE matches
    ADD COLUMN external_match_id VARCHAR(64);

CREATE UNIQUE INDEX uk_matches_external_match_id
    ON matches (external_match_id)
    WHERE external_match_id IS NOT NULL;

COMMENT ON COLUMN matches.external_match_id IS
    'Provider-side match id; unique when present / 供应商侧比赛 ID，有值时唯一';

-- ---------------------------------------------------------------------------
-- 2) asian_odds_snapshots 唯一键加入 total_line
-- ---------------------------------------------------------------------------
-- 现键是 (match_id, provider_code, bookmaker_code, captured_at, handicap_line)。
-- 单源 provider 一家庄会同时报 N 档让球与 M 档大小球，而本表一行要同时承载
-- 两个市场，因此落库形态是 N×M 交叉积：同一 captured_at 下会出现多行
-- 同 handicap_line、不同 total_line。沿用旧键必撞唯一约束。
--
-- total_line 可空（只报让球不报大小球的庄），而 PostgreSQL 的唯一约束里
-- NULL 互不相等。这里仍是安全的：交叉积只会对「同时存在的大小球档位」
-- 展开，同一 handicap_line 不会产生第二行 NULL total_line。
ALTER TABLE asian_odds_snapshots
    DROP CONSTRAINT uk_asian_odds_snapshots_dedupe;

ALTER TABLE asian_odds_snapshots
    ADD CONSTRAINT uk_asian_odds_snapshots_dedupe UNIQUE (
        match_id,
        provider_code,
        bookmaker_code,
        captured_at,
        handicap_line,
        total_line
    );

COMMENT ON CONSTRAINT uk_asian_odds_snapshots_dedupe ON asian_odds_snapshots IS
    'Dedupe key including total_line for handicap x totals cross product / 含大小球线的交叉积去重键';

-- ---------------------------------------------------------------------------
-- 3) match_result_facts.score_scope：比分口径显式化
-- ---------------------------------------------------------------------------
-- 亚盘结算只认 90 分钟比分。加时与点球必须能被显式拒绝，而不是靠「源里恰好没有」。
-- 默认值为 REGULATION_90，使现有 writer 与存量行零改动即可继续工作。
ALTER TABLE match_result_facts
    ADD COLUMN score_scope VARCHAR(16) NOT NULL DEFAULT 'REGULATION_90';

ALTER TABLE match_result_facts
    ADD CONSTRAINT ck_match_result_facts_score_scope CHECK (
        score_scope IN ('REGULATION_90', 'INCLUDING_EXTRA_TIME', 'INCLUDING_PENALTIES')
    );

COMMENT ON COLUMN match_result_facts.score_scope IS
    'Score scope; asian settlement only accepts REGULATION_90 / 比分口径，亚盘结算只接受 90 分钟';

-- ---------------------------------------------------------------------------
-- 4) 赛果事实来源白名单加入 NovaAPI
-- ---------------------------------------------------------------------------
-- V18 的触发器要求 OFFICIAL 事实必须引用 SPORTTERY_RESULT 载荷。接入第二家
-- 竞彩数据源后，若不放宽 provider 白名单，赛果写入会被整批拒绝。
--
-- 这里刻意不新增 ProviderDataTypeEnum 取值：SPORTTERY_RESULT 描述的是
-- 「竞彩官方赛果」这一数据语义，而不是哪家公司提供的；来源靠 provider_code 区分。
-- 这样 AdminSyncRunQueryServiceImpl 的箭头 switch 不需要改，
-- raw_data_payloads / data_sync_runs 两条 CHECK 约束也不需要改
-- （箭头 switch 语句漏 case 不报错、只静默丢值，能不改就不改）。
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
        AND payload_data_type = 'SPORTTERY_RESULT'
        AND payload_provider_code IN ('CHINA_SPORTTERY', 'NOVA_API') THEN
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

COMMENT ON FUNCTION validate_match_result_fact_payload() IS
    'Require result provenance to match an official or manual raw payload / 校验赛果来源与官方或人工原始载荷一致';

-- ---------------------------------------------------------------------------
-- 5) predictions 发布诊断列
-- ---------------------------------------------------------------------------
-- 目的：让「这场为什么没发出去」能从库里查，而不是只活在日志里。
-- 自动发布每轮都会重扫当天未发布场次，没有落库的失败原因就只能靠翻日志。
--
-- 这两列刻意不加入 protect_published_prediction() 的 ROW 列表：
-- 它们不是预测内容（方向、概率、置信度），而是发布动作的副产品，
-- 必须在 PUBLISHED 之后仍可更新；若纳入不可变守卫，第一次发布就会失败。
-- 这也与「新增预测内容列必须同步改触发器」那条约束不冲突——那条约束保护的是
-- 已发布预测背后的概率，不是发布尝试的计数。
ALTER TABLE predictions
    ADD COLUMN publish_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_publish_error VARCHAR(200);

ALTER TABLE predictions
    ADD CONSTRAINT ck_predictions_publish_attempt_count CHECK (publish_attempt_count >= 0);

COMMENT ON COLUMN predictions.publish_attempt_count IS
    'Auto-publish attempt counter / 自动发布尝试次数';
COMMENT ON COLUMN predictions.last_publish_error IS
    'Last auto-publish failure reason, truncated / 最近一次自动发布失败原因';
