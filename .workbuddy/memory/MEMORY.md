# 竞彩罗盘 · 项目长期约定

## 分支与交付

- 分支前缀 `feat/<topic>`（不用 `codex/`）。**不按任务拆分支**：一个交付分支承载同一目标下的多个连续任务。
- 同一时刻只允许一个任务 `IN_PROGRESS`；任务 `DONE` 不触发合并，整个交付完成后才合并 Draft PR。
- 当前交付分支：`feat/nova-single-source`（NovaAPI 单源改造，6 阶段，计划见 `~/.workbuddy/plans/quantum-thunder-einstein-9P5tHcJF.md`）。

## 验证策略与测试分层（用户明确要求减负）

- 纯计算/映射/校验 → 普通单测，秒级，不启动容器。
- **涉及 Flyway 迁移、PostgreSQL 专有约束/触发器 → 必须在本机真实 PG 16 容器验证一次**。这是唯一不能用「项目能启动」替代的环节。
- `mvn test`（125+ 个 `*Test.java`）**零 Docker**；只有 18 个 `*IT.java` 需要 Docker，仅在 `-Pintegration` 下执行。Testcontainers 固定 1.21.4；IT 未开 `.withReuse(true)`，冷启动 ~15s。
- **仓库 CI 没有前端门禁**：唯一工作流只跑 `mvn -Pintegration verify`。纯前端任务本地 `npx vitest run` + `tsc -b` + `vite build` 即可收档，别等 CI。

## 用户偏好

- 个人自用，数据源合规不作为阻塞项。务实优先：能跑通优先于流程完备，但接受「关键节点必须验证」的论证。
- 中文交流，回答要有明确结论而非罗列选项。

## 亚盘模型的硬约束（不得违反）

- **共识定线 + 真实行冻结**：决策线由跨庄聚合选出，但 `predictions.asian_odds_snapshot_id` 必须指向一条**确实报出该线**的真实快照行（复合外键 `(id, match_id)`）。不要用共识公平价计账——公平价无法成交。
- **方向与置信度有意不同源**：方向取自模型概率（v5 起，两主盘反解 λ 后投影），置信度按冻结行真实报价算。「共识方向明确却判低置信」不是 bug。
- **主盘残差不是自洽性检验**：两方程两未知数恰定，残差必然趋近 0（实测 `1e-16`）。判断市场打架只能看**邻线回投**（家数 ≥2 且非主盘线）；`lineCount == 0` 要单打 `no-neighbor`。
- **edge 门槛只能是影子**：让球方向的模型概率是把 λ 投影回**同一条主盘线**，而 λ 由该线去水概率反解 → `edge ≡ 0`。任何正 `safety-margin` 会拒发 100% 让球方向，调参无效。真判据应是 `margin = 模型概率 − 1/赔率`（含抽水）。
- **σ 可以真拦，但两种 source 不可比**：σ = 跨庄去水概率总体标准差，与 λ 求解无关，适合硬过滤（阈值 `0.0200`，严格大于才拒，分市场独立判定）。白名单按时达标时样本从十几家掉到两三家 → σ 系统性偏小，`source=whitelist` 与 `fallback` 不可横向比较。
- 聚合顺序固定 **逐家去水 → 中位 → 归一**；主盘 = 偏离 50/50 最小的线（不是众数），线型偏好仅在精确同分时生效。
- 统一概率口径 `p_eff = (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)`，市场侧与模型侧逐字一致。
- **落库口径是首边，不是所选方向**：`model_cover_prob`/`market_cover_prob`/`edge_cover` 按主队（让球）与**大球**（大小球）记；`AsianPublishGate.Verdict` 里的概率已按方向做过 `1 − p`。
- **新增落库列必须同时改三处**：迁移列定义 + `protect_published_prediction()` 两处 ROW 列表 + `PredictionImportDto` 校验。精度统一 `numeric(9,6)`（生成侧 `PROBABILITY_SCALE = 6`，5 位会静默四舍五入）。
- **「值只在某分支才算」是落库陷阱**：给市场新增落库列前，先确认该值在**所有分支**都被计算。

## 统计与公开验证的硬约束

- **两种产品线统计不可混算**：响应用 `markets` 数组而**不是** `had`/`hhad` 具名字段——具名字段把「HAD/HHAD 必然存在」写进响应形状。竞彩与亚盘按 `predictionType` 分别请求，首页固定亚盘。
- **走盘进 Brier 样本、不进命中率分母**：`outcome = HIT 1 / HALF_WIN .75 / PUSH .5 / HALF_LOSS .25 / MISS 0`；`settled` 不含 PUSH，另给 `weightedHitRate`。
- **哈希结构只能追加不能修改**：V4 = V3 字段序 + 十列（6 位定标）。全非空 → v4、全空 → v3、部分为空抛错。`verifyStoredHash` 必须回落认「十列已填但哈希按 v3 算」的存量行。
- **「部分为空抛错」会让单边方向的早期亚盘行永远发不出去**：只发布让球方向、大小球无方向时，`model_over_prob`/`market_over_prob`/`edge_over`/`totals_dispersion` 四列为空而其余六列有值 → 算不出 `prediction_hash` → 撞 `ck_predictions_publish_lifecycle`。实测 `predictions.id=41`（周三004）、`48`（周三013）属此形态。已发布的 10-22 那批（49~61）十列全填，说明生成侧现在会双边都算；**判一个 DRAFT 行能否合法发布，先查十列是否全填**。
- **`ck_predictions_publish_lifecycle` 要求四样同时写**：`DRAFT` 时 `publish_time`/`lock_time`/`prediction_hash` 全为 NULL，`PUBLISHED`/`LOCKED` 时三者全非空且 `publish_time < lock_time`。所以「先补 `lock_time` 再改状态」必失败，唯一合法入口是 `PredictionMapper.publishDraft`。
- **批量写操作按 `(matchId, predictionId)` 升序串行 + 逐条独立事务**：同场多条都要更新 `matches` 行，并发反向拿锁会死锁。前端必须按 ID 回填（服务端会重排）。
- **`switch` 语句必须补 `default` 抛错**：Java 箭头 `switch` **语句**不要求穷尽、编译不报错，新增枚举值被静默丢弃。
- **后台盘口阶梯必须复用生成侧的查询与聚合器**，否则看到的不是模型当时用的数据。
- **历史复盘不要写 `settlements`**：从未真实发布的行（`DRAFT`/`lock_time` 空/无发布审计）一旦进结算表，公开统计的命中率与 Brier 会把「文档里的模型输出」当成「已发布产品」计入，事后无法区分。正确做法是用生产纯函数算（`AsianMarketTermsResolver.resolve` → `MarketSettlementCalculatorRouter.calculate` → 净利按 `SettlementMarginSupport.netProfit` 同规则换算），结果落文档。注意 `SettlementMarginSupport` 是**包内私有**，`com.jingcaicompass.tools` 下的脚手架调不到，只能复述换算规则。
- **10-21 那 8 场（id 41~48）实测复盘**：1 命中 / 2 走盘 / 7 未中，加权命中率 `0.2500`、净利 `-6.17`；唯一命中是周三008 的大球 3.50。两个走盘都在整数让球盘（008 主 -2.00 净胜 2、009 主 -1.00 净胜 1），是结构不是判对。8 场总进球均值 3.75 而 5 个大小球方向有 4 个小球 → 该读成「市场当天整体低估进球」。

## NovaAPI 单源：接口字段的硬约束（抓包 + 官方文档双核实）

1. **`matchStartDate` 才是实际开赛日期**，`matchDate` 是竞彩业务日。2026-10-21 抓包 13 场里 **10 场跨零点**。开赛时间必须 `matchStartDate + matchTime` 按 `Asia/Shanghai` 拼。
2. **`matchStatus` 是销售状态不是比赛状态**（`Selling`/`SoldOut`/`Closed`）→ 映射 switch 必须显式处理 `SoldOut` 并带 `default` 抛错。赛程接口没有「比赛是否结束」字段，`matches.match_status=FINISHED` 只能由赛果写入方设置。
3. **赛果有两套队名**：`allHomeTeam`/`allAwayTeam` = 全称、`homeTeam`/`awayTeam` = 简称。赛程 `homeTeamName` 对应**全称**，用简称会静默改写 `matches` 队名。
4. **`poolStatus` 跨接口同名不同义**：赛程里是玩法销售状态、赛果里是奖池派奖状态。赛果 21 场里 **2 场 `poolStatus` 为空但 `sectionsNo999` 与 `matchResultStatus=2` 齐全** → 用它过滤结算会静默漏单。
5. **字段类型会漂移**：`sellStatus` 是字符串 `"2"`；`leagueId` 文档写 String 实际是数字。一律 `asText()` 读——`asInt()` 读文本会静默变 0。
6. **`sectionsNo999` = 竞彩 90 分钟比分（已实证）**。2026-10-21 解放者杯 `2042118`（`tfStatusShort=PEN`）：竞彩开奖 `0:1` = `soccer-events.ftHomeScore` `0:1`，≠ 点球 `5:3`。守卫在 `NovaApiMatchResultScoreScopeTest`。
7. **赛果 `matchDate` 是开赛自然日，不是竞彩业务日**（与赛程 `matchDate` 同名不同义）。10-21 业务日 13 场里 10 场跨零点，赛果返回 `matchDate=10-22`。按 `(matchDate, matchNumStr)` 定位会**查不到**这 10 场（编号带业务日星期前缀，不会串场；但相差整数周的同名场次会撞）→ **定位只能用 `matchId`（= `matches.external_match_id`）**。
8. **`/nova/text/match-results` 只返回已开奖场次**：未来日期返回 `results: []`，同日 13 场赛程只有 10 场已开奖。故「有记录」=「已开奖」，`matchResultStatus` 实测 31/31 为 `2`；非 `2` 时解析器**整体失败**（逐条跳过会让该场永久停在 PENDING）。
9. **`etHomeScore/etAwayScore` 是加时阶段自身比分，不是累计**：`2042118` `ft=0:1` 而 `et=0:0`，若按累计理解不可能小于 `ft`。任何 `ft + et` 换算都会重复计入加时进球。

- `/nova/tf/odds?matchId=` **一场一次调用**即返回全部机构×全部玩法（`marketId=4` 亚盘让球、`5` 大小球、`1` 胜平负）。`data.matchId` 就是竞彩 matchId，`data.idEvent` 是 Nova 内部 ID。
- 让球 `line` = **主队让球数**（负=主让），同档 Home/Away 文本相同；`Over/Under` 大小球同 line。与 `AsianOddsLineDto.handicapLine`「主队让球为负」契约天然一致，直接用。
- **文档读取方法**：`https://novaapi.tsgnkj.com/` 是 Vite SPA，字段说明内嵌在 `/assets/index-*.js` bundle 里，没有独立 API 端点。抓 bundle 按字段名定位明文描述。

## 单源改造进度与事实

- 阶段 0（V25 迁移）、阶段 1（赛程）、阶段 2（赔率）、阶段 3（自动发布）、**阶段 4（赛果与结算，`600f3dc`）** 已完成并推送。**阶段 4 实跑 2026-10-22 通过**：首轮 `SUCCESS 23/23`（`appended=10/unchanged=13`）、次轮 `appended=0/unchanged=23`、13 条事实全 `FINAL/REGULATION_90/OFFICIAL`、13 场 `FINISHED` 且比分与开奖一致、跨零点 10 场全部按 `external_match_id` 命中。**阶段 2 实跑验收 2026-10-22 通过**：13 场真实同步 2052 行交叉积快照、两轮增量 `2052→0`、13 场预测全部冻结在 `pinnacle` 真实行、开赛后 `skippedLive=12` 且零新增。**阶段 3 实跑验收同日通过**：13 候选全发布、次轮 `NO_PENDING_MATCH` 短路、每场仅 1 条 PUBLISHED、审计操作者 `system:auto-publish`。
- **`quota_cost` 曾恒为 0（已修）**：`ProviderHttpExecutor` 只按响应头解析额度（The Odds API 的 `x-requests-*` 约定），NovaAPI 不返回任何额度头 → 5000 次/日上限不可见。修法在 `NovaApiClient.get()` 单点按「请求 1 + 每次重试 1」计。**换源时要检查新源的额度口径是否也走响应头**，否则额度监控静默失效。
- **赔率取数与落库的四条硬约束（阶段 2 建立）**：
  1. `/nova/tf/odds?matchId=` **一场一次调用**即返回全部机构×玩法，额度 = 场次数；`data.matchId` 是竞彩 ID、`data.idEvent` 是 Nova 内部 ID。不要照搬 The Odds API 的「按联赛 sport key 批量拉」。
  2. **`line` 是主队让球数，不取反**。The Odds API 的让球两侧互为相反数，NovaAPI 同档两侧 `line` **文本完全相等**；照搬旧配对逻辑会 100% 判 `INCONSISTENT_SPREAD_PAIR`（表现为「解析成功但零条 line」）。
  3. **落库形态是交叉积**（让球线 × 大小球线），因为冻结行要求同一行同时具备两个市场。配套必须同时改 V25 唯一键与 `AsianOddsSnapshotWriter` 去重维度（含 `total_line` + `IS NULL`），否则每轮把整批重插一遍且数据库不报错。
  4. **大小球保留档按「跨庄去水概率离 50/50 的距离」取前 4，不按报出家数**。按家数会选中 `1.5/2.5/3.5/4.5`（每家都报的稀疏整数盘），把真正的四分盘主盘 `2.75/3.0` 排掉 → 聚合器把主盘定到 2.5，一切看起来正常但共识已错。
- **`code != 200` 必须整体失败**：NovaAPI 把「密钥过期」放在响应体而 HTTP 仍是 200，逐场降级会让同步记录显示成功、快照 0 条。单场 404 才降级为该场的 `parseError`。同理，`NovaApiOddsProvider` 遇到 HTTP 401/403 立即抛错，不把同一份凭据问题刷 N 遍。
- **三条读查询不再 JOIN `match_source_mappings`，改按 `provider_code` 过滤**。这个过滤不是可选优化：聚合器去重键是 `(bookmaker, line)` 不含 provider，混源的同名庄会互相覆盖并一起进中位数。过滤值来自 `ActiveAsianOddsProvider`（构造自 `app.asian-odds.provider`）。
- **NovaAPI 赔率响应没有「是否滚球」标志**，只能在同步侧用 `matches.kickoff_time` 判赛前；否则开赛后的赔率会被当成赛前共识写进快照，下游任何校验都发现不了。
- **`NovaApiClient` 的装配条件是 OR**（`app.sporttery.provider=nova` 或 `app.asian-odds.provider=NOVA_API`），见 `NovaApiEnabledCondition`。限速（30 次/分 → 固定 2 秒间隔）也放在这个客户端里，因为赛程/赔率/赛果共用同一份额度。
- **`matches` 必须写全 `league_id`/`home_team_id`/`away_team_id`**：联赛下拉由前端从 `match.leagueId !== null` 反推，不填会让筛选**静默消失**。字典解析由 `MatchReferenceDictionary` 负责（缓存只到一次 `writeAll`；名字为空时保留既有归属）。
- **不给 `leagues.name_zh`/`teams.name_zh` 加唯一索引**：多处 IT 用固定名插入，本机无 Docker 跑不了 IT，加约束只会在 CI 才暴露。这是「本机验证能力决定迁移激进度」的一般原则。
- **迁移类 IT 禁止 `flyway(null).migrate()` + 断言迁移数**：一律 `flyway("<target>")` 限定目标版本；全量末版本断言只留 `PostgresApplicationIT` 一处（加迁移必须同步改）。
- `backend/src/main/java/db/migration/` 下有 Java 版 Flyway 迁移（V22），新建 SQL 前先 `ls` 避免撞号。当前最高 **V25**。
- **赛果落库的三条约定（阶段 4 建立）**：
  1. **`amended=true`**：单源之后比分变化只可能是竞彩改判，`amended` 守卫原本防的是跨源误读。仍生效的两条：终态不得回退 `PENDING`、官方事实不得被人工事实替代。
  2. **`providerUpdatedAt` 取响应信封 `timestamp`**（该接口没有逐条更新时间）。用信封时间戳而非 `now()`，同一份载荷回放保持幂等，审计时间也不漂移。
  3. **`score_scope` 不主动写**（保持 V25 的 DB 默认 `REGULATION_90`）：口径由「解析器只读 `sectionsNo999`」在边界保证，写常量不增加信息。
- **赛果窗口按开赛自然日覆盖** → 业务日 D-1 的跨零点场次会出现在 D 的窗口里。D-1 赛程没采集过时这批会记 `match not found`（实测 `data_sync_runs.id=52/53`：`23/13/10`），**新库上线先补采前一天赛程**（补采后 `id=56/57` → `SUCCESS 23/23`）。
- **`prediction_status` 有 DB 触发器只允许 `DRAFT → PUBLISHED`**，别想用 SQL 直接置 `LOCKED` 走捷径；发布服务同时校验「发布截止时间」与「比赛状态为 SCHEDULED/LOCKED」，所以**历史 DRAFT 永远补不回来**。
- **生成是条件幂等**（阶段 3 修正）：`generatedAt` 取自已持久化特征快照，赔率快照没变时重跑命中同一 `generationBatchHash` → `reused=N / inserted=0`；只有赔率刷新后快照变了才插新批次、版本号按 `MAX(version)+1` 递增。所以 `regenerate-interval` 的真实价值是**省掉无谓的生成计算**，不是防批次爆炸。亚盘同步则是**追加快照**，重跑后方向/置信度可能翻转（不是 bug）。

## 已知待办 / 未收口

- 邻线校验口径（整条阶梯取最大 vs 主盘 ±0.75）**本轮不改代码**，结论写进 `docs/asian-model-design.md`，待下一阶段连同 `neighbor-tolerance` 取窗口一起处理。
- 一次性实跑脚手架 `backend/src/test/java/com/jingcaicompass/tools/` 下文件保持未跟踪（会让本地单测数比 CI 多 1~2，别误判）。
- 阶段 5：删映射层与旧数据源（`match_source_mappings` 结构保留、停止写入；`ChinaSportteryProvider`、`TheOddsApiProvider`、5 个映射/归一化服务、后台页面）。
- 阶段 4 唯一欠的证据：**官方赛果 → 锁定 → 结算的全链真跑**。10-21 那批（41~48）已用结算纯函数补算并落文档（不落库，见上），但仍不算全链证据；10-22 的 13 条 `PUBLISHED`（开赛 10-23 00:45）次日自然锁定结算后补证据。
- `docs/business-data-flow.md` 仍在描述映射层（阶段 5 一起改），阶段 4 没有动它。
