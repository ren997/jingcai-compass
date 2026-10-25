-- V27 / 修正 V25 的两处自相矛盾声明
--
-- 背景：V25 是单源改造的结构前置迁移，写完后只在本机以 local profile 启动验证过，
-- 从未在 CI 的 Testcontainers PG 16 上跑过。2026-10-25 分支首次 push 到 master 后
-- CI 才暴露这两个缺陷，27 个集成用例因此变红。两处都是「声明与实际能力不一致」，
-- 不是测试写错。

-- ---------------------------------------------------------------------------
-- 1) score_scope 列宽不足以容纳它自己声明的取值
-- ---------------------------------------------------------------------------
-- V25 把列声明为 VARCHAR(16)，同一段里的 CHECK 却允许
--   'INCLUDING_EXTRA_TIME'（20 字符）与 'INCLUDING_PENALTIES'（19 字符）。
-- 两个合法取值一写入就报 value too long for character varying(16)，
-- CHECK 因此形同虚设：本该「能落库、由结算侧显式拒绝」的口径，
-- 退化成插入即失败，那场比赛会永久停在待结算——正是要避免的形态。
--
-- V25 的注释写的是「加时与点球必须能被显式拒绝，而不是靠源里恰好没有」，
-- 这句话只有在取值可存储时才成立。宽度按最长取值留余量取 24。
ALTER TABLE match_result_facts
    ALTER COLUMN score_scope TYPE VARCHAR(24);

COMMENT ON COLUMN match_result_facts.score_scope IS
    'Score scope; asian settlement only accepts REGULATION_90 / 比分口径，亚盘结算只接受 90 分钟';

-- ---------------------------------------------------------------------------
-- 2) 赛果来源校验回退到 V18 口径（OFFICIAL 分支只看 data_type）
-- ---------------------------------------------------------------------------
-- V25 给 OFFICIAL 分支加了 provider_code IN ('CHINA_SPORTTERY', 'NOVA_API')。
-- 这条白名单在原理上无法强制，落地后只是制造故障：
--   * raw_data_payloads.provider_code 是 VARCHAR(64) 自由文本，没有任何域约束，
--     白名单只是把「应用层枚举」这句口头约定写进了数据库函数；
--   * 每接入一个赛果来源就要再写一次迁移（V25 本身已经吃过一次这种苦）；
--   * 实际后果是 STUB provider 的赛果同步整批失败，以及 25 个集成用例的
--     fixture（用 'T401_PROVIDER_x' 这类占位符）全部被拒。
--
-- 真正需要守住的是「OFFICIAL 事实必须引用竞彩官方赛果载荷」，
-- 这一点由 data_type = 'SPORTTERY_RESULT' 表达，也是 V10/V18 一直以来的口径。
-- 来源身份记录在载荷行上、后台 sync-runs 可查，不需要在触发器里复述。
-- MANUAL 分支的 'MANUAL_ENTRY' 保留：人工赛果只有一个写入方，这一条是可强制的。
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

COMMENT ON FUNCTION validate_match_result_fact_payload() IS
    'Require result provenance to match an official or manual raw payload / 校验赛果来源与官方或人工原始载荷一致';
