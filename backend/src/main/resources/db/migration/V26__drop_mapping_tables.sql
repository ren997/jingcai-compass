-- 阶段 5：删除映射层五张表。
--
-- 单源（NovaAPI）之后「供应商赛事/实体 → 竞彩场次/内部联赛球队」的映射不再存在：
--   * 赔率与赛果都直接按 matches.external_match_id 定位，不查任何映射表；
--   * 联赛与球队名称直接来自赛程/赔率载荷，不再做名称归一化；
--   * 后台映射复核、标准化复核页面与接口已在阶段 5 的前几批删除。
--
-- 因此这五张表既无写入方也无读取方，属于死数据（生产库实测 45 行，全部属于
-- 已停用的 THE_ODDS_API，NOVA_API 一行都没有）。
--
-- 依赖方向：这五张表的外键全部指向外部（matches / leagues / teams），
-- 没有任何表反向引用它们，所以直接 DROP 即可，无需先删约束。
-- 表上由 V14~V17 增加的列、索引与 NOT NULL 调整随之丢弃。
--
-- 历史迁移（V2/V4/V5/V14~V17/V19、Java 版 V22）保持原样：Flyway 的 checksum 不能回改，
-- 且新库仍需按序重建到 V25 再执行本迁移。

DROP TABLE IF EXISTS match_source_mappings;
DROP TABLE IF EXISTS provider_team_mappings;
DROP TABLE IF EXISTS provider_league_mappings;
DROP TABLE IF EXISTS team_aliases;
DROP TABLE IF EXISTS league_aliases;
