# 竞彩罗盘 · 项目长期约定

## 用户偏好

- 个人自用，数据源合规不作为阻塞项。务实优先：能跑通优先于流程完备，但接受「关键节点必须验证」的论证。
- 中文交流，回答要有明确结论而非罗列选项。
- **不要凭记忆断言某能力不存在**：先查 `docs/data-sources-novaapi.md`（完整端点清单）与官方 bundle，再下结论。曾因漏记 `odds-history` 而误告「没有历史赔率」。

## 分支与验证策略

- 分支前缀 `feat/<topic>`（不用 `codex/`）。**不按任务拆分支**；同一时刻只允许一个任务 `IN_PROGRESS`，整个交付完成后才合并 Draft PR。当前：`feat/nova-single-source`。
- 纯计算/映射/校验 → 普通单测。**涉及 Flyway 迁移、PG 专有约束/触发器 → 必须本机真实 PG 16 容器验证一次**，这是唯一不能用「能启动」替代的环节。
- `mvn test`（125+ `*Test.java`）**零 Docker**；18 个 `*IT.java` 需 Docker，仅 `-Pintegration` 跑。
- **仓库 CI 没有前端门禁**（只跑 `mvn -Pintegration verify`）：纯前端任务 `npx vitest run` + `tsc -b` + `vite build` 即可收档。
- **迁移类 IT 禁止 `flyway(null).migrate()` + 断言迁移数**：一律 `flyway("<target>")`；全量末版本断言只留 `PostgresApplicationIT`。
- **「本机验证能力决定迁移激进度」**：本机无 Docker 跑不了 IT，就不加只在 CI 才暴露的约束（如 `leagues.name_zh`/`teams.name_zh` 唯一索引——多处 IT 用固定名插入）。

## 亚盘模型的硬约束（不得违反）

- **共识定线 + 真实行冻结**：决策线由跨庄聚合选出，但 `predictions.asian_odds_snapshot_id` 必须指向一条**确实报出该线**的真实快照行（复合外键 `(id, match_id)`）。不要用共识公平价计账——公平价无法成交。
- **方向与置信度有意不同源**：方向取自模型概率（v5 起两主盘反解 λ 后投影），置信度按冻结行真实报价算。「共识方向明确却判低置信」不是 bug。
- **主盘残差不是自洽性检验**：两方程两未知数恰定，残差必然趋近 0（实测 `1e-16`）。判市场打架只能看**邻线回投**（家数 ≥2 且非主盘线）；`lineCount == 0` 单打 `no-neighbor`。
- **edge 门槛只能是影子**：让球方向概率是把 λ 投影回**同一条主盘线**，而 λ 由该线去水概率反解 → `edge ≡ 0`。任何正 `safety-margin` 会拒发 100% 让球方向，调参无效。真判据应是 `margin = 模型概率 − 1/赔率`（含抽水）。
- **σ 可以真拦，但两种 source 不可比**：σ = 跨庄去水概率总体标准差，与 λ 无关，适合硬过滤（阈值 `0.0200`，严格大于才拒，分市场独立判定）。白名单达标时样本从十几家掉到两三家 → σ 系统性偏小，`whitelist` 与 `fallback` 不可横向比较。
- 聚合顺序固定 **逐家去水 → 中位 → 归一**；主盘 = 偏离 50/50 最小的线（不是众数），线型偏好仅在精确同分时生效。
- 统一概率口径 `p_eff = (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)`，市场侧与模型侧逐字一致。
- **落库口径是首边，不是所选方向**：`model_cover_prob`/`market_cover_prob`/`edge_cover` 按主队（让球）与**大球**（大小球）记；`AsianPublishGate.Verdict` 里的概率已按方向做过 `1 − p`。
- **新增落库列必须同时改三处**：迁移列定义 + `protect_published_prediction()` 两处 ROW 列表 + `PredictionImportDto` 校验。精度统一 `numeric(9,6)`。
- **「值只在某分支才算」是落库陷阱**：加列前先确认该值在所有分支都被计算。
- **交叉积落库会丢掉大小球档（已修 `7e4b631`）**：`DISTINCT ON (…, handicap_line)` 使每组只留最大 `total_line` 档 → 冻结行必然落在被抬高的线 → 大小球系统性偏小球。修法：五元组去重专供选行、`alignedWithTotalsLine` 提到排序键首位、判定线 ≠ 主盘线整场跳过。让球聚合不受影响。详见 `docs/reports/2026-10-22-asian-predictions.md` §3。

## 统计与公开验证的硬约束

- **两种产品线统计不可混算**：响应用 `markets` 数组而**不是** `had`/`hhad`。竞彩与亚盘按 `predictionType` 分别请求，首页固定亚盘。
- **走盘进 Brier 样本、不进命中率分母**：`HIT 1 / HALF_WIN .75 / PUSH .5 / HALF_LOSS .25 / MISS 0`；`settled` 不含 PUSH，另给 `weightedHitRate`。
- **哈希结构只能追加不能修改**：V4 = V3 字段序 + 十列。全非空 → v4、全空 → v3、部分为空抛错。`verifyStoredHash` 必须回落认「十列已填但哈希按 v3 算」的存量行。
- **「部分为空抛错」会让单边方向的早期行永远发不出去**：只发让球方向时四个 over 列为空 → 算不出 `prediction_hash` → 撞约束。**判 DRAFT 能否合法发布，先查十列是否全填**。
- **`ck_predictions_publish_lifecycle` 要求四样同时写**：`DRAFT` 时 `publish_time`/`lock_time`/`prediction_hash` 全 NULL，`PUBLISHED`/`LOCKED` 时全非空且 `publish_time < lock_time`。唯一合法入口是 `PredictionMapper.publishDraft`。
- **`prediction_status` 有 DB 触发器只允许 `DRAFT → PUBLISHED`**，别想用 SQL 置 `LOCKED`。发布服务同时校验发布截止时间与比赛状态，**历史 DRAFT 永远补不回来**。
- **批量写按 `(matchId, predictionId)` 升序串行 + 逐条独立事务**：同场多条都更新 `matches` 行，并发反向拿锁会死锁。前端必须按 ID 回填。
- **`switch` 语句必须补 `default` 抛错**：Java 箭头 `switch` **语句**不要求穷尽、编译不报错，新枚举值被静默丢弃。
- **后台盘口阶梯必须复用生成侧的查询与聚合器**，否则看到的不是模型当时用的数据。
- **历史复盘不要写 `settlements`**：从未真实发布的行进结算表会污染公开统计，事后无法区分。用生产纯函数算（`AsianMarketTermsResolver.resolve` → `MarketSettlementCalculatorRouter.calculate` → `SettlementMarginSupport.netProfit`）落文档。注意 `SettlementMarginSupport` **包内私有**，`tools` 下脚手架调不到。
- **结算要按「实际发布的判定线」**：已发布内容锁死在旧线（`protect_published_prediction()` 禁改），重算只代表当前代码行为，不是开奖依据。两套口径必须分开列。
- **跨批次 ROI 绝不能直接汇总**：10-21 批 `−6.17`、10-22 批 `+4.435`，同模型差 10.6 单位，差别在判定线口径。多批次统计前先确认该批有无「未报主盘线」的场次。
- **算重算口径的盈亏必须重查水位**：新判定线 = 大小球主盘档，该档赔率与旧线完全不同（旧线抬高后小球 `1.57~1.75`，主盘档 `1.85~2.07`）。按五元组精确匹配 `asian_odds_snapshots`，不能复用旧冻结行赔率。

## 已结算批次的复盘结论（引用注意口径）

- **10-21 批（id 41~48）**：10 方向 / HIT 1 / MISS 7 / PUSH 2 / `−6.17` / 命中率 12.5%。唯一命中周三008 大球 3.50；两个走盘都在整数让球盘（结构不是判对）；4 个小球方向全灭而 8 场总进球均值 3.75 → 「市场当天整体低估进球」。
- **10-22 批旧线（真实开奖）**：17 方向 HIT 10 / HALF_WIN 3 / MISS 4 = `+4.435`（让球 5 全中 `+4.350`、大小球 12 条 `+0.085`）。按「修复后是否还会发布」拆：**A 组（仍发，5 场）`−2.065`、B 组（不发，7 场）`+2.150`** → 正收益几乎全来自让球。
- **10-22 批修复后重算**：10 方向，**让球 `+4.350` 一字未变、大小球 `−1.730`（实亏）、合计 `+2.620`、加权命中率 `67.50%`**（旧 72.06%）。① 大小球去掉污染后**本来就是亏的**，与「13 个大小球盈亏余量原全为负」吻合；② A 组同 5 场只改善 `+0.335`（001 由赢变亏、003 由亏变赢、006 半赢微增）；③ `−1.815` 差额里 `−2.150` 来自 B 组消失。**修复让账面回归真实，不是让模型变准；旧口径命中率也不能用。**
- **让球全中不等于模型强**：盘口仅 ±0.25~1.25、比分全是单边大胜/双杀、方向 `edge ≡ 0`。样本 5 条不能当能力证据。

## NovaAPI 接口字段的硬约束

1. **`matchStartDate` 才是实际开赛日期**，`matchDate` 是竞彩业务日（13 场里 10 场跨零点）。开赛时间 `matchStartDate + matchTime` 按 `Asia/Shanghai` 拼。
2. **`matchStatus` 是销售状态不是比赛状态**（`Selling`/`SoldOut`/`Closed`）→ 映射 switch 必须显式处理 `SoldOut` 并带 `default` 抛错。赛程接口没有「比赛是否结束」字段，`FINISHED` 只能由赛果写入方设置。
3. **赛果有两套队名**：`allHomeTeam`/`allAwayTeam` = 全称、`homeTeam`/`awayTeam` = 简称。赛程 `homeTeamName` 对应**全称**，用简称会静默改写队名。
4. **`poolStatus` 跨接口同名不同义**：赛程里是玩法销售状态、赛果里是奖池派奖状态。赛果里 2 场 `poolStatus` 空但比分与 `matchResultStatus=2` 齐全 → 用它过滤会静默漏单。
5. **字段类型会漂移**：`sellStatus` 是字符串 `"2"`、`leagueId` 文档写 String 实际是数字。一律 `asText()` 读——`asInt()` 读文本会静默变 0。
6. **`sectionsNo999` = 竞彩 90 分钟比分（已实证）**：解放者杯 `2042118`（`PEN`）竞彩开奖 `0:1` = `ftHomeScore`，≠ 点球 `5:3`。守卫在 `NovaApiMatchResultScoreScopeTest`。
7. **赛果 `matchDate` 是开赛自然日**（与赛程同名不同义）。按 `(matchDate, matchNumStr)` 查不到跨零点场次 → **定位只能用 `matchId`**（= `matches.external_match_id`）。
8. **`/nova/text/match-results` 只返回已开奖场次**：未来日期 `results: []`。非 `2` 时解析器**整体失败**（逐条跳过会让该场永久停在 PENDING）。
9. **`etHomeScore/etAwayScore` 是加时阶段自身比分，不是累计**：`ft=0:1` 而 `et=0:0`。`ft + et` 会重复计入加时进球。
10. **`/nova/tf/odds?matchId=` 一场一次调用**返回全部机构×玩法（`marketId=4` 让球、`5` 大小球、`1` 胜平负）；`data.matchId` 是竞彩 ID、`data.idEvent` 是 Nova 内部 ID。让球 `line` = **主队让球数**（负=主让），同档两侧文本相同，**不要取反**。
11. **`/nova/tf/odds-history` 存在且可用（2026-10-23 实测，别再忘）**：每个 option 带 `points[]`（`{odd, updateTime}` 升序）= **变动快照非定时采样**。取「≤T 最后一条」就是 T 时刻**精确**价格（分段常值），不是近似。**回溯约 4 个月**（06-20 有、06-02 空），赛程 `all-list?date=` 可查 06-01。**历史赔率不需要自己攒。** 偶发上游 500（连不上 `/112.126.29.186:8080`），换 `idEvent` 即恢复 → 按可重试处理，别当「无历史」。
12. **接口文档在哪**：SPA 无 OpenAPI，但字段定义内嵌 `assets/index-*.js`。`grep -bo '__name:"tf-odds-history"'` 取**字节**偏移（含中文）→ 字节切片 → 提 `n=V([...])` 入参、`o=`/`a=`/`r=` 逐层返回字段。同 bundle 有 30+ 端点清单。
13. **多参数签名需按字典序全签**（`matchId=..&marketId=..` 漏签 401，错误信息会列出服务端参与验签的键）。生产 `NovaApiClient` 已正确，探针未处理。

## 单源改造进度与实现事实

- 阶段 0（V25）、1（赛程）、2（赔率）、3（自动发布）、4（赛果与结算，`600f3dc`）全部完成并推送。阶段 4 验收：`SUCCESS 23/23`、次轮全 `unchanged`、13 条事实全 `FINAL/REGULATION_90/OFFICIAL`、跨零点 10 场按 `external_match_id` 全命中。
- **`quota_cost` 曾恒为 0（已修）**：`ProviderHttpExecutor` 只按响应头解析额度（The Odds API 的 `x-requests-*` 约定），NovaAPI 不返回额度头 → 5000 次/日上限不可见。修法在 `NovaApiClient.get()` 单点按「请求 1 + 每次重试 1」计。**换源时必须检查新源额度口径是否也走响应头**。
- **落库形态是交叉积**（让球线 × 大小球线）：冻结行要求同一行同时具备两个市场。必须同时改 V25 唯一键与 `AsianOddsSnapshotWriter` 去重维度（含 `total_line` + `IS NULL`），否则每轮整批重插且数据库不报错。**大小球保留档按「跨庄去水概率离 50/50 的距离」取前 4，不按报出家数**（按家数会选中 `1.5/2.5/3.5/4.5` 稀疏整数盘，把真正主盘 `2.75/3.0` 排掉 → 主盘定到 2.5，一切正常但共识已错）。
- **`line` 不取反**：The Odds API 两侧互为相反数，NovaAPI 同档两侧文本完全相等；照搬旧配对逻辑会 100% 判 `INCONSISTENT_SPREAD_PAIR`（表现为「解析成功但零条 line」）。
- **`code != 200` 必须整体失败**：NovaAPI 把「密钥过期」放响应体而 HTTP 仍 200，逐场降级会让同步记录显示成功、快照 0 条。单场 404 才降级 `parseError`；401/403 立即抛错。
- **三条读查询按 `provider_code` 过滤，不 JOIN `match_source_mappings`**：聚合器去重键是 `(bookmaker, line)` 不含 provider，混源同名庄会互相覆盖并一起进中位数。
- **NovaAPI 赔率响应没有「是否滚球」标志**，只能在同步侧用 `matches.kickoff_time` 判赛前。
- **`NovaApiClient` 装配条件是 OR**（`app.sporttery.provider=nova` 或 `app.asian-odds.provider=NOVA_API`），见 `NovaApiEnabledCondition`。限速（30 次/分 → 固定 2 秒间隔）也在这里，赛程/赔率/赛果共用同一份额度。
- **`matches` 必须写全 `league_id`/`home_team_id`/`away_team_id`**：联赛下拉由前端从 `match.leagueId !== null` 反推，不填会让筛选**静默消失**。字典解析由 `MatchReferenceDictionary` 负责（缓存只到一次 `writeAll`；名字为空时保留既有归属）。
- **赛果落库三约定**：① `amended=true`（终态不得回退 `PENDING`、官方事实不得被人工事实替代）；② `providerUpdatedAt` 取响应信封 `timestamp`（保证回放幂等）；③ `score_scope` 不主动写（保持默认 `REGULATION_90`）。
- **赛果窗口按开赛自然日覆盖** → 业务日 D-1 的跨零点场次出现在 D 的窗口。**新库上线先补采前一天赛程**，否则记 `match not found`。
- **生成是条件幂等**（`generatedAt` 取自已持久化特征快照，赔率没变时命同 `generationBatchHash` → `reused=N / inserted=0`）；**亚盘同步是追加快照**，重跑后方向/置信度可能翻转（不是 bug）。
- `backend/src/main/java/db/migration/` 有 Java 版 Flyway 迁移（V22），新建 SQL 前先 `ls` 避撞号。当前最高 **V25**。

## 本地实跑手法

- 探针 `tools/novaapi-probe/NovaApiProbe.java`（单文件直接 `java` 跑），`raw <path> <query>` 打任意端点，产物落 `novaapi-probe-out/<timestamp>/`（`/` 与 `-` 都转 `_`）。**每次运行新建目录**：`ls -t | head -1` 会取到最近那次（可能是不想要的 401 响应），多文件比对用 `ls -S`。
- 凭据经**环境变量**传入、**不落盘**：应用侧 `NOVA_API_KEY` / `NOVA_PRIVATE_KEY`，探针用 `NOVA_PRIVATE_KEY_B64`（值同为 base64 PKCS8）。日卡会过期，过期需用户重新下发。
- **本地跑单源必须用 `@SpringBootTest(properties=...)` 覆盖 provider**：`application-local.yml` 里两个 provider 是硬编码字符串（非 `${ENV:default}`），yml 优先级高于 application.yml 占位符 → 环境变量 `SPORTTERY_PROVIDER` / `ASIAN_ODDS_PROVIDER` **无效**。
- 全链路一步到位：`SportteryPoolSyncService.sync(...)` → `AsianOddsSyncService.sync(...)` → `PredictionAutoPublishOrchestrator.run(d)`（内部含生成节流，生成与发布分两次独立提交）。
- 赛果抓包存档命名：`backend/src/test/resources/sporttery/novaapi-match-results-<start>-to-<end>.json`。
- **本地 `app.tasks.enabled=false`，所有定时 Job 全关**：已发布预测开赛过后仍停在 `PUBLISHED`（`lock_time` 有值但不转 `LOCKED`）——**每天必须手动跑**。「锁定后首页 500」那个 P0 因此在本地从未触发，上线前必须先确认它是否还在。
- **修复后口径的线上形态（2026-10-23 首批验证）**：8 条已发布预测让球与大小球 `edge` 全为 `0.000000`。两种「不发」的正常形态：① 开赛后才同步赔率的场次丢弃；② 主盘档两侧水位在 `1.90~1.98` 平衡区的场次两市场都判 LOW → **整场跳过**（12 场里 3 场）。**「场场都有方向」才是异常。**
- **导出盘口必须标明侧别**：让球「主 X @y / 客 -X @z」，大小球「线 大 @a / 小 @b」；只写 `(y/z)` 读者无法判断哪边是大球。
- **补算已开赛场次要离线复刻、不写库**（`AsianOddsSyncServiceImpl.lookupMatch` 的开场守卫正是为拦这个）：`AsianMarketConsensusAggregator.aggregate` 与 `AsianPublishGate` 都是纯函数，常量抄出即可（`minBookmakers=2` / `minWhitelistBookmakers=2` / 白名单 `pinnacle,matchbook,betfair` / MEDIUM 信号 `0.0250` / HIGH `0.0750` / 抽水门槛 `0.1000`·`0.1600` / σ `0.0200`；排序键 `imbalance → lineKindRank → sampleCount desc → line`）。**手写复刻必须拿已入库场次交叉校验**（曾用 002 校验到小数点后六位一致）。

## 已知待办 / 未收口

- 邻线校验口径（整条阶梯取最大 vs 主盘 ±0.75）本轮不改代码，结论写进 `docs/asian-model-design.md`。
- 一次性实跑脚手架 `backend/src/test/java/com/jingcaicompass/tools/` 保持未跟踪（本地单测数比 CI 多 1~2，别误判）；已写入 `.gitignore`（连同含明文库凭据的 `tools/verify-stage2/`）。
- 阶段 5：删映射层与旧数据源（`match_source_mappings` 结构保留、停止写入；`ChinaSportteryProvider`、`TheOddsApiProvider`、5 个映射/归一化服务、后台页面）。
- 阶段 4 唯一欠的证据：**官方赛果 → 锁定 → 结算的全链真跑**。目前结算均为离线核对，未走 `SettlementService`。
- **回测功能未开工**。已定底线：① 结果落独立 `backtest_*` 三表，**不进 `settlements`**；② 计算复用生产纯函数；③ 每次 run 记四个版本号（git sha / 模型版本 / **选行逻辑版本** / provider）；④「无泄漏」做成硬断言（参与计算的快照 `captured_at < kickoff_time`，赛果只进结算不进特征）；⑤ 统计口径固定 HIT/HALF_WIN/PUSH/HALF_LOSS/MISS，走盘进 Brier 不进命中率分母。历史赔率可回溯 4 个月，样本不必等积累。
- `docs/business-data-flow.md` 仍在描述映射层（阶段 5 一起改）。
