# 竞彩罗盘 · 长期约定

## 用户偏好

- 个人自用，合规不作阻塞；务实优先，关键节点必须验证。中文、给结论而非罗列选项。要求「发在会话里」就不写文件。
- **不凭记忆断言某能力不存在**：先查 `docs/data-sources-novaapi.md` 端点清单。

## 流程与验证

- 分支 `feat/<topic>`；同一时刻只一个任务 `IN_PROGRESS`。迁移最高 **V28**（`db/migration/` 有 Java 版 V22，新建 SQL 先 `ls` 避撞号）。
- **「本地能启动」≠「CI 能过」**：CI 是空 Testcontainers PG16、V1~V28 全量重放、`stub` provider、65 个 IT 主动撞约束——声明矛盾类缺陷只在这里暴露。
- 纯计算→单测；**Flyway 迁移 / PG 专有约束 / 触发器→必须真实 PG16 跑过**。无 Docker 用「生产库 `BEGIN`→执行→`ROLLBACK`」验语法（验不出声明矛盾）。无 Docker 就别加只在 CI 才暴露的约束（如 `leagues.name_zh` 唯一索引）。
- `mvn -o test` 零 Docker（722 项）；`*IT.java` 仅 `-Pintegration`，迁移类一律 `flyway("<target>")`。CI 无前端门禁：`vitest run` + `tsc -b` + `vite build` 即收档。
- 触发器 `RAISE ... USING CONSTRAINT='x'` 的约束名**不进 JDBC message**，断言只能比主消息文本。

## 亚盘模型硬约束

- **共识定线 + 真实行冻结**：`predictions.asian_odds_snapshot_id` 必须指向确实报出该线的真实快照行（复合外键 `(id, match_id)`）——公平价无法成交。
- **方向与置信度有意不同源**：方向取模型概率（两主盘反解 λ 后投影回冻结行）；置信度按冻结行报价 `margin = 1/o1+1/o2-1`、`signal = |1/o1-1/o2|/total`，HIGH `margin≤.1000 且 signal≥.0750`，MEDIUM `margin≤.1600 且 signal≥.0250`。**临场越平衡 signal 越小，方向明确却判 LOW 不是 bug。**
- **`edge ≡ 0`**：λ 由该主盘去水概率反解再投影回同一条线必然相等 →「模型 X% / 市场 X% / edge 0.0%」是恒等式、零信息（前端已折叠）。任何正 `safety-margin` 会拒发 100% 让球方向；真判据应是 `模型概率 − 1/赔率`。
- **主盘残差不是自洽性检验**（恰定方程组，实测 `1e-16`）；判市场打架只能看**邻线回投**（家数 ≥2 且非主盘线），`lineCount==0` 单打 `no-neighbor`。
- **σ 可真拦但两种 source 不可比**：σ = 跨庄去水概率总体标准差，`>0.0200` 才拒、分市场独立；白名单达标时样本掉到两三家 → σ 系统性偏小。
- 聚合顺序 **逐家去水 → 中位 → 归一**；主盘 = **偏离 50/50 最小的线**（非众数），口径 `p_eff = (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)`。**落库口径是首边**（主队/大球），`AsianPublishGate.Verdict` 里的概率已按方向做过 `1 − p`，不能混用。
- **首页主盘必须复用生成侧聚合器**：`DISTINCT ON (match_id) ORDER BY captured_at DESC` 取到交叉积**最后写入的一行**，与「最平衡」无关（实测 005 显示 `+0.5`、真实 `0`）。已改用 `AsianMarketConsensusAggregator` + `AsianFreezeSnapshotSelector`：展示线必须等于决策并结算的那行，无完整水位就留空。
- **决策出口共 13 个、11 个枚举值**，顺序即语义（`INSUFFICIENT_ASIAN_CONSTRAINTS` 必须先于 `NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE`）；`INCONSISTENT_ASIAN_MARKETS` 有 `!feasible()` 与残差/邻线超限两个出口，不能合并。

## 存储与发布

- **哈希结构只能追加**（V4 = V3 + 十列）：全非空→v4、全空→v3、**部分为空抛错**。只发单边方向时四个 over 列为空 → 撞约束；**判 DRAFT 能否发布先查十列是否全填**。
- **`ck_predictions_publish_lifecycle` 要求四样同时写**（`publish_time`/`lock_time`/`prediction_hash` 与状态一致且 `publish_time < lock_time`）；唯一合法入口 `PredictionMapper.publishDraft`。触发器只允许 `DRAFT→PUBLISHED`，**历史 DRAFT 永远补不回来**。
- **新增落库列必须同时改三处**：迁移列 + `protect_published_prediction()` 两处 ROW 列表 + `PredictionImportDto` 校验；精度 `numeric(9,6)`，加列前确认该值在所有分支都被计算。
- **批量写按 `(matchId, predictionId)` 升序串行 + 逐条独立事务**（反向拿锁会死锁）。

## 统计口径

- **两种产品线不可混算**：响应用 `markets` 数组而非 `had`/`hhad`，按 `predictionType` 分别请求，首页固定亚盘。
- **走盘进 Brier、不进命中率分母**：`HIT 1 / HALF_WIN .75 / PUSH .5 / HALF_LOSS .25 / MISS 0`，`settled` 不含 PUSH。
- **历史复盘不要写 `settlements`**：用生产纯函数算（`AsianMarketTermsResolver` → `MarketSettlementCalculatorRouter` → `SettlementMarginSupport.netProfit`，后者**包内私有**）落文档。按「实际发布的判定线」结算；**跨批次 ROI 绝不能直接汇总**，重算必须按五元组重查水位。
- **批次结论只信同口径同批次**：10-21 `−6.17`（12.5%）；10-22 修复后 `+2.620`（加权 67.50%，旧 72.06%）——**修复让账面回归真实，不是模型变准**，旧口径命中率不能用；让球全中不算能力证据。明细见 `docs/reports/`。

## NovaAPI 接口

1. **`matchStartDate` 才是实际开赛日期**，`matchDate` 是竞彩业务日（13 场 10 场跨零点）；开赛时间 = 两者按 `Asia/Shanghai` 拼。**赛果 `matchDate` 却是开赛自然日** → 定位只能用 `matchId`（= `matches.external_match_id`）。
2. **`matchStatus` 是销售状态**（`Selling`/`SoldOut`/`Closed`）→ switch 必须处理 `SoldOut` 且带 `default` 抛错；`FINISHED` 只由赛果写入方设置。
3. **两套队名**：`allHomeTeam`/`allAwayTeam` 全称、`homeTeam`/`awayTeam` 简称；赛程 `homeTeamName` 对应全称，用简称会静默改写队名。`poolStatus` 跨接口同名不同义（赛程=玩法销售、赛果=派奖），用它过滤赛果会漏单。
4. **字段类型会漂移**（`sellStatus` 是 `"2"`、`leagueId` 文档写 String 实为数字）→ 一律 `asText()`，`asInt()` 读文本会静默变 0。
5. **`sectionsNo999` = 竞彩 90 分钟比分（已实证）**：竞彩开奖 `0:1` = `ftHomeScore` ≠ 点球；`matchResultStatus != 2` 即未开奖；`etHome…` 是加时阶段自身比分，**`ft + et` 会重复计入**。守卫 `NovaApiMatchResultScoreScopeTest`。
6. **`/nova/text/match-results` 只返回已开奖场次**；非 `2` 时解析器**整体失败**（逐条跳过会让该场永久停在 PENDING）。
7. **`/nova/tf/odds` 一场一次调用**返回全部机构×玩法（`marketId=4` 让球、`5` 大小球）。让球 `line` = **主队让球数**（负=主让），同档两侧文本相同**不要取反**（The Odds API 相反，照搬必判 `INCONSISTENT_SPREAD_PAIR`）。
8. **回溯深度（10-25 实测）**：赛程/赛果 **≥16 个月**；**`odds-history` 仅约 4 个月**（`06-10` 404 → `06-15` 200 但只剩 1 个变动点 → `06-20` 起完整），是回测唯一硬约束。历史赔率仅 **7~8 家**（实时 10+），σ 不可与线上阈值互比。老场次响应达 **20.8s**/388KB 逼近 `read-timeout: 20s` → 归档放宽 60s + 重试。`points[]` 升序 = **变动快照非定时采样**，取「≤T 最后一条」即 T 时刻精确价。
9. **`updateTime` 是北京时间不是 UTC**；生产 `NovaApiOddsPayloadMapper.parseTime` 按 UTC 解释——**回测做无泄漏断言必须按 `Asia/Shanghai`**，否则赛前 1h45m 变成 9h45m，断言照样通过但时序全错。
10. **赛前约 1h45m 停更** →「收盘价」实为 T-105min 价，别声称拿到临场价。
11. **多参数签名按字典序全签**（漏签 401 会列出参与验签的键）。接口文档内嵌 `assets/index-*.js`（`grep -bo '__name:"<id>"'` 取偏移），读法见 `docs/data-sources-novaapi.md`。

## 单源与任务

- **迁移现状**：生产库 v28。V26 删映射层五张表（备份 `.workbuddy/tmp/mapping-backup-20261025-145650/`）、V27 修 `score_scope`、V28 加宽 `request_key`。
- **落库是交叉积**（让球线 × 大小球线），冻结行要求同一行具备两个市场。必须同时改 V25 唯一键与 `AsianOddsSnapshotWriter` 去重维度（含 `total_line` + `IS NULL`），否则每轮整批重插且不报错。**大小球保留档按「去水概率离 50/50 的距离」取前 4，不按报出家数**。
- **`code != 200` 必须整体失败**（密钥过期放响应体而 HTTP 仍 200）；单场 404 才降级，401/403 立即抛错。
- **读查询按 `provider_code` 过滤、不 JOIN 映射表**：去重键 `(bookmaker, line)` 不含 provider。赔率响应**没有滚球标志**，只能用 `kickoff_time` 判赛前。`NovaApiClient` 装配条件是 OR，限速与赛程/赔率/赛果共用额度。
- **`matches` 必须写全 `league_id`/`home_team_id`/`away_team_id`**（联赛下拉由 `leagueId !== null` 反推，不填会让筛选**静默消失**）。**赛果三约定**：`amended=true`、`providerUpdatedAt` 取信封 `timestamp`、`score_scope` 不主动写。
- **生成条件幂等**（赔率没变则同 `generationBatchHash`）；**亚盘同步追加快照**，重跑后方向/置信度可能翻转（不是 bug）。`MISSING_CONFIRMED_ASIAN_MARKET` = 该场无任何亚盘快照。
- **按当日实体拼接的键都要算上界**：`request_key` 每场 8 字符，256 列宽 29 场溢出 → V28 加宽 1024 + 超 900 字符退化为「场次数 + SHA-256 前 16 字节」。
- **`ProviderSyncTemplate` 必须自兜底**：原只 catch `fetcher.fetch()`，`savePayload`/`link` 在外，异常直冒调度层 → 记录永久 `RUNNING`、额度显示 0。已拆 `persistAndParse` + `finishUnexpectedFailure`。**见 `RUNNING` + `quota=0` + 无 payload，先查写库异常，别当卡死。**
- **`@Scheduled` 没有任何超时**：`fixedDelay` =「上次结束 + 延迟」，未定制 `TaskScheduler` → 默认 `pool.size=1`，全部 Job 挤一个线程；`read-timeout` 是 socket 级。已加 `SyncRunRecoveryService`/`Runner`（`started_at < now-10min` 的 `RUNNING` → `FAILED`；放 `ApplicationRunner`）。
- **`@ConditionalOnBean` 对配置类里的 `@Bean` 在组件扫描阶段恒为 false**：八个 Job 里七个有 `@Bean` 兜底，唯独 `PredictionAutoPublishJob` 只有 `@Component` + `@ConditionalOnBean(Orchestrator)` → **从未创建过**（10-23 起无新预测且零日志）。已改 `@Bean` + `@ConditionalOnProperty`。**判据：actuator 无 `jingcai.job.execution` = 一个 job 都没记过账。**
- **自动发布没有「赛前 N 小时」窗口**：条件只有 `kickoff_time > now` + `DRAFT` + 该模型版本尚无公开版本，生成受 `regenerate-interval: 3h` 节流；开赛后才补的场次被 `KICKOFF_PASSED` 挡掉、补不回来。
- **没有映射层也没有旧源**：`SportteryProviderType` 只剩 `NOVA|STUB`、`AsianOddsProviderTypeEnum` 只剩 `STUB|NOVA_API`；`app.sporttery`/`app.asian-odds` 下的 base-url、api-key、超时、重试**从来没被读过**（Nova 只用 `app.nova`）。

## 本地实跑

- 探针 `tools/novaapi-probe/NovaApiProbe.java`（单文件 `java` 直跑）。凭据走环境变量：`NOVA_API_KEY`/`NOVA_PRIVATE_KEY`，探针用 `NOVA_PRIVATE_KEY_B64`；日卡会过期。
- **本机 `mvn` 不在 PATH**：export `JAVA_HOME=/c/D/java/tools/jdk/jdk-21.0.11+10`，`PATH` 加 `apache-maven-3.9.16/bin`；`spring-boot:run` 要在 `-f backend/pom.xml` 下跑。`gh` 未装 → 网页建 PR。
- **本地跑单源必须 `@SpringBootTest(properties=...)` 覆盖 provider**（`application-local.yml` 是硬编码字符串）。
- **`app.tasks.enabled: true` 不等于 Job 全开**：`application-local.yml` 只显式开了 5 个，`asian-odds`/`data-pipeline`/`snapshot-publish` 取默认 `false`，bean 不创建——亚盘缺失先查这里。只验「能不能启动」要覆盖 `--app.tasks.enabled=false`。
- **本地启动必须显式 `--server.port=8080 --management.server.port=8081`**：WorkBuddy 注入的 55726 会被 `${SERVER_PORT:8080}` 取到。
- **Flyway 在 Web 服务器启动之前执行**：端口冲突导致 `FAILED TO START` 时迁移**已提交落库**。
- **`NoResourceFoundException` 被 `GlobalExceptionHandler` 当 500 返回**（应为 404）。公开接口只有 `GET /api/public/matches`、`POST /api/public/matches/{list,detail}`、`POST /api/public/predictions/detail` 与快照下载。
- **两种正常「不发」**：开赛后才同步赔率的场次丢弃；主盘两侧 `1.90~1.98` 平衡 → 两市场都 LOW → 整场跳过。**场场都有方向才是异常**；导出盘口必须标侧别。
- **补算已开赛场次离线复刻、不写库**：纯函数直接 `new` + `backend/target/classes`，**不要手写公式**（手写版已两次末位漂移）。
- **无头截图可验前端**：`msedge.exe --headless=new --disable-gpu --hide-scrollbars --window-size=1000,1100 --virtual-time-budget=9000 --screenshot=...`（本机无 playwright/puppeteer）。

## 回测（P1~P6 已实现，真实区间未跑过）

- 原型 `tools/hist-backtest/HistoryReplay.java`：odds-history 的 `points[]` 按「≤T 最后一条」切截面 → 伪装成交叉积 → 交给生产同一批纯函数。**已校验**：001（2042155）在 `10-23 15:14:21` 截面与实盘补算逐位一致。**决策时点必须显式固定**（同一场 T-45h/T-24h/T-18h 让球主 MEDIUM、T-9h/T-6h LOW、T-3h 客 MEDIUM、收盘 LOW）。
- 计划 `~/.workbuddy/plans/radiant-beacon-turing-27C472nZ.md`；分支 `feat/historical-backtest`；设计文档 `docs/backtest-design.md`。
- **进度（10-26）**：P1 判定核心抽取 → P2 归档 → P3 客户端 → P4 编排/结算/指标/API（`efb5953`）→ P5 前端（`1bc2eaf`）→ P6 文档（`da77588`）。后端 **838** 项全绿（基线 820+18），前端 **101** 项（+21）、`tsc -b` 与 `vite build` 通过。**迁移到 v29 且已应用生产库**（`installed_on 23:03:16`，checksum `-1164027409` 与文件一致，无需 repair）。
- **三条「同一份代码」**：判定调 `AsianDecisionEngine`、结算调 `MarketSettlementCalculatorRouter` + `SettlementMarginSupport.netProfit`（只提权为 public，公式未改）、指标调 `StatisticsCalculator`。复制一份公式 = 下次改四分盘规则时有一边静默不同步。
- **无泄漏三道闸**：DB CHECK（`decision_at < kickoff_time`、`frozen_captured_at <= decision_at`）+ **状态传 `SCHEDULED`**（库里是 `FINISHED`，直接传会被 `NOT_SCHEDULED` 出口吃掉 → 结果是「没样本」而非「判错」，极难发现）+ 切片只取 `<=T`。`updateTime` 是北京时间，断言必须按 `Asia/Shanghai`。
- **两套计数不能合并**：`total*` 用于展示、`batch*` 用于判终态；合并会让「中途某批全失败」被后续成功批次掩盖成 `SUCCEEDED`。标 SUCCEEDED 前强制校验无 `PENDING` 样本。
- **`settleOnce` 的 `WHERE result_status='PENDING'` 返回 0 行是唯一「已结算过」信号** → 读待结算样本必须只取 PENDING（`selectPendingByRunId`）。
- **`brierDelta = market − model`，正数才是模型更好，负值是合法结论**。市场 Brier 不是 0.25 而是 **0.1875 量级**（走盘记 0.5 恰在「市场恒 0.5」那腿拿满分）；押次边要取 `1−p`（漏了模型会算成 0.45²）。展示上用 `formatScore`（**不乘 100**）+ 文字判断（优于/不如/持平）不着色——页面已用正红负绿表收益，再接一个红会一色两义。
- **口径版本号四个**：`aggregation_version` / `selector_version` / `model_version` / `builder_version`。不 bump 不是报错，而是**两份结论看起来同源**。`selector` 排序第一键是对齐大小球主盘（10-22「13/13 全小球」的修复），回测用不含该键的实现则冻结行不同而断言全过。
- **归档必须自建 `odds_history_archive`，不复用 `raw_data_payloads`**（raw 按 run 计当前态、`data_type` 有 CHECK 白名单）。**归档失败分四档**：`SUCCESS`/`NO_HISTORY`(404)/`NO_QUOTE_YET`(空序列) 可负缓存；`RETRYABLE_FAILURE` 必重采；`PERMANENT_FAILURE`(400/401/403) 重采只重复错误。**HTTP 200 + 体里 `code=500`** 是上游偶发（换 `idEvent` 即 200），`ProviderHttpExecutor` 看不到响应体。
- **`CHAR(n)` 出库补空格**会让指纹查表静默 miss（`run_key`/`group_key`/`payload_hash` 一律 VARCHAR）。静态断言 `doesNotContain("CHAR(32)")` 会被 `VARCHAR(32)` 命中，得用 `(?<!VAR)CHAR\(`。核对已应用版本与文件是否同源：`tools/backtest-schema-verify/V29ChecksumCheck.java`（按 Flyway `ChecksumCalculator` 逐行 `readLine`+CRC32 重算）。
- **`RestClient` 无 per-call 读超时**（工厂级）→ 历史 60s 与实时 20s 只能拆两个 bean。
- **持久化服务只加 `@ConditionalOnBean(DataSource)` 不够**：组件扫描期恒 false，必须 `PersistenceServicesAutoConfiguration` 补 `@Bean`；controller 侧用 `ObjectProvider` 惰性取，否则无 DataSource 的测试上下文起不来。
- **前端**：参数组固定三行（合计/让球/大小球）——`ALL` 不是两市场相加；默认区间取**最近 30 天**而非「回溯起点到今天」（后者 129 天超 120 上限，一进页面预估按钮就灰，写测试时才暴露）；预算确认是人的动作，预估不自作主张带 `confirmBudget`；`PARTIAL` 用警告色而非成功色；antd 两字按钮自动插空格（「续 跑」），测试断言用正则。
- **未做**：create → worker → 指标整链**在真实库上没跑过一次**；`BacktestMigrationIT` 未真跑（本地无 Docker）。

## 前端约定

- **结论优先、推导折叠**：卡片主内容是「主 -0.5 / 大 2.5」，赔率与置信度副行，模型概率/edge/λ/σ 收进 `<details>`；置信度只做轻量色标。方向文案统一 `asianHandicapVerdictText` / `totalGoalsVerdictText`（平手盘写「平手」），亚盘线一律 `formatAsianLine`。

## 已知待办

- 阶段 4 唯一欠证：**官方赛果 → 锁定 → 结算全链真跑**（结算目前均离线核对）；「锁定后首页 500」的 P0 本地从未触发，上线前必须确认。
- 「已发布预测开赛过后停在 PUBLISHED」仍在，需每天手动处理。
- 上线路线：`T106`/`T107` 两周观测 → `T108` Go/No-Go → `T604` Docker 部署 → `T605` MVP 验收。
