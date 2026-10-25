-- V28 / 加宽 raw_data_payloads.request_key，修「场次一多就同步失败」
--
-- 背景：NovaAPI 亚盘赔率是「一场一次调用」，一次同步把当日全部场次拿回来后聚合成
-- 一份载荷入库，request_key 形如
--   nova-odds:2042213,2042214,...,2042241:2026-10-25T10:00:00Z:2026-10-26T00:30:00Z
-- 也就是把当日所有 matchId 直接拼进去，长度随场次数线性增长。
--
-- 2026-10-25 三个 ASIAN_ODDS 同步全部失败，库里同步记录停在 RUNNING、额度为 0、
-- 一条 payload 都没落。日志给出的原因很直接：
--   ERROR: value too long for type character varying(256)
--   INSERT INTO raw_data_payloads (... request_key ...)
-- 当日 29 场拼出的键是 283 字符，超出 256 共 27 个字符。
--
-- 为什么接入时没发现：10-21~10-23 每天只有 12~13 场，历史最长 request_key 刚好 156，
-- 26 个字符的余量在 17 场左右就耗尽。平时看不出问题，周末赛程一多就必然踩中。
--
-- 并发缺陷：失败发生在 savePayload 的 INSERT，而该语句在 ProviderSyncTemplate 里位于
-- 「fetch 的 try」之外，因此 DataIntegrityViolationException 直接冒泡到 Job 层，
-- finishFailed 从未执行 —— 同步记录永久停在 RUNNING。加宽列修的是症状，
-- 生成侧另加了长度守卫（见 NovaApiOddsProvider#requestKey），避免以后无限增长。

-- 1024 字符：request_key 只含 ASCII（matchId、逗号、ISO 时间戳），1024 字符即 1024 字节，
-- 仍在 btree 索引项 1/3 page 的限制内，唯一索引 uk_raw_data_payloads_dedupe 可直接重建。
ALTER TABLE raw_data_payloads
    ALTER COLUMN request_key TYPE VARCHAR(1024);

COMMENT ON COLUMN raw_data_payloads.request_key IS
    '业务幂等键；NovaAPI 赔率为「一日一场次一次」聚合载荷，长度随场次数增长，故取 1024（V28 由 256 加宽）';
