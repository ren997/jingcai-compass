# 竞彩罗盘 · 项目长期约定

## 用户偏好

- 个人自用，数据源合规不作阻塞项；务实优先，但关键节点必须验证。
- 中文交流，给明确结论而非罗列选项；要求「发在会话里」就不要生成文件。
- **不要凭记忆断言某能力不存在**：先查 `docs/data-sources-novaapi.md` 端点清单（曾漏记 `odds-history` 误告「没有历史赔率」）。

## 流程与验证

- 分支前缀 `feat/<topic>`，不按任务拆分支；同一时刻只一个任务 `IN_PROGRESS`。**分支合并前必须让 CI 真跑过一次**——`feat/nova-single-source` 攒了 41 个提交没建过 PR，合并后首轮 CI 直接红（27 个集成用例），根因全是阶段 0 的 V25 从未在 Testcontainers PG 16 上执行过。
- 纯计算 → 单测；**Flyway 迁移、PG 专有约束/触发器 → 必须真实 PG 16 上跑过一次**。本机无 Docker 时可用「生产库 `BEGIN`→执行→`ROLLBACK`」验证 DDL 语法与效果（见 `V27` 的验法）。
- `mvn -o test` 零 Docker（716 项，11 跳过为本地实跑脚手架）；18 个 `*IT.java` 仅 `-Pintegration`（CI 65 项）。**CI 无前端门禁**，纯前端任务 `vitest run` + `tsc -b` + `vite build` 即可收档。
- 迁移类 IT 一律 `flyway("<target>")`，禁 `flyway(null).migrate()` + 断言迁移数。本机无 Docker 就不加只在 CI 才暴露的约束（如 `leagues.name_zh` 唯一索引——多处 IT 用固定名插入）。
- **触发器 `RAISE ... USING CONSTRAINT='x'` 的约束名不进 JDBC message**（只有真 CHECK 违例会输出 `violates check constraint "x"`）；断言只能比主消息文本。

## 亚盘模型硬约束

- **共识定线 + 真实行冻结**：决策线由跨庄聚合选出，但 `predictions.asian_odds_snapshot_id` 必须指向**确实报出该线**的真实快照行（复合外键 `(id, match_id)`）——公平价无法成交。
- **方向与置信度有意不同源**：方向取模型概率（v5 起两主盘反解 λ 后投影），置信度按冻结行报价算：`margin = 1/o1+1/o2-1`，`signal = |1/o1-1/o2|/total`；HIGH `margin≤.1000 且 signal≥.0750`，MEDIUM `margin≤.1600 且 signal≥.0250`。**临场盘越平衡 signal 越小，共识方向明确却判 LOW 不是 bug。**
- **`edge ≡ 0`**：λ 由该主盘去水概率反解、再投影回同一条线必然相等。任何正 `safety-margin` 会拒发 100% 让球方向。真判据应是 `模型概率 − 1/赔率`。
- **主盘残差不是自洽性检验**（恰定方程组，实测 `1e-16`）；判市场打架只能看**邻线回投**（家数 ≥2 且非主盘线），`lineCount==0` 单打 `no-neighbor`。
- **σ 可真拦但两种 source 不可比**：σ = 跨庄去水概率总体标准差，阈值 `0.0200` 严格大于才拒，分市场独立。白名单达标时样本从十几家掉到两三家 → σ 系统性偏小。
- 聚合顺序固定 **逐家去水 → 中位 → 归一**；主盘 = **偏离 50/50 最小的线**（不是众数）。口径 `p_eff = (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)`。
- **落库口径是首边**（主队 / 大球），`AsianPublishGate.Verdict` 里的概率已按方向做过 `1 − p`，不能混用。
- **交叉积落库会丢大小球档（已修 `7e4b631`）**：`DISTINCT ON (…, handicap_line)` 只留最大 `total_line` → 冻结行被抬高 → 大小球系统性偏小球。见 `docs/reports/2026-10-22-asian-predictions.md` §3。

## 存储与发布

- **哈希结构只能追加**（V4 = V3 + 十列）：全非空 → v4、全空 → v3、**部分为空抛错**。只发单边方向时四个 over 列为空 → 算不出哈希 → 撞约束，**判 DRAFT 能否发布先查十列是否全填**。
- **`ck_predictions_publish_lifecycle` 要求四样同时写**（`publish_time`/`lock_time`/`prediction_hash` 与状态一致且 `publish_time < lock_time`）；唯一合法入口 `PredictionMapper.publishDraft`。DB 触发器只允许 `DRAFT → PUBLISHED`，**历史 DRAFT 永远补不回来**。
- **新增落库列必须同时改三处**：迁移列 + `protect_published_prediction()` 两处 ROW 列表 + `PredictionImportDto` 校验；精度 `numeric(9,6)`。加列前确认该值在所有分支都被计算。
- **批量写按 `(matchId, predictionId)` 升序串行 + 逐条独立事务**（反向拿锁会死锁）。**`switch` 必须补 `default` 抛错**（箭头 switch 不要求穷尽，新枚举值被静默丢弃）。后台盘口阶梯必须复用生成侧的查询与聚合器。

## 统计与批次结论

- **两种产品线不可混算**：响应用 `markets` 数组而非 `had`/`hhad`，按 `predictionType` 分别请求，首页固定亚盘。
- **走盘进 Brier、不进命中率分母**：`HIT 1 / HALF_WIN .75 / PUSH .5 / HALF_LOSS .25 / MISS 0`，`settled` 不含 PUSH。
- **历史复盘不要写 `settlements`**（未真实发布的行会污染公开统计）：用生产纯函数算（`AsianMarketTermsResolver` → `MarketSettlementCalculatorRouter` → `SettlementMarginSupport.netProfit`，后者**包内私有**）落文档。**按「实际发布的判定线」结算**，重算只代表当前代码行为，两套口径分开列。
- **跨批次 ROI 绝不能直接汇总**（10-21 `−6.17` vs 10-22 `+4.435`，差在判定线口径）；重算盈亏必须按五元组重查水位。
- **10-21（id 41~48）**：10 方向 / HIT 1 / MISS 7 / PUSH 2 / `−6.17` / 命中率 12.5%。4 个小球全灭而 8 场总进球均值 3.75 → 市场当天整体低估进球。
- **10-22**：旧线 17 方向 `+4.435`；修复后 10 方向让球 `+4.350`、**大小球 `−1.730` 实亏**、合计 `+2.620`、加权命中率 `67.50%`（旧 72.06%）。**修复让账面回归真实，不是模型变准；旧口径命中率也不能用。** 让球全中不算能力证据（盘口仅 ±0.25~1.25、比分全是单边大胜、`edge ≡ 0`）。

## NovaAPI 接口

1. **`matchStartDate` 才是实际开赛日期**，`matchDate` 是竞彩业务日（13 场里 10 场跨零点）；开赛时间 = `matchStartDate + matchTime` 按 `Asia/Shanghai` 拼。**赛果 `matchDate` 却是开赛自然日** → 定位只能用 `matchId`（= `matches.external_match_id`）。
2. **`matchStatus` 是销售状态**（`Selling`/`SoldOut`/`Closed`）→ switch 必须处理 `SoldOut` 且带 `default`；`FINISHED` 只能由赛果写入方设置。
3. **两套队名**：`allHomeTeam`/`allAwayTeam` 全称、`homeTeam`/`awayTeam` 简称；赛程 `homeTeamName` 对应**全称**，用简称会静默改写队名。`poolStatus` 跨接口同名不同义（赛程=玩法销售、赛果=派奖），用它过滤赛果会漏单。
4. **字段类型会漂移**（`sellStatus` 是 `"2"`、`leagueId` 文档写 String 实为数字）→ 一律 `asText()`，`asInt()` 读文本会静默变 0。
5. **`sectionsNo999` = 竞彩 90 分钟比分（已实证）**：解放者杯 `2042118`（`PEN`）竞彩开奖 `0:1` = `ftHomeScore`，≠ 点球 `5:3`。`matchResultStatus != 2` 即未开奖。`etHomeScore/etAwayScore` 是加时阶段自身比分（`ft=0:1` 而 `et=0:0`），`ft + et` 会重复计入。守卫 `NovaApiMatchResultScoreScopeTest`。
6. **`/nova/text/match-results` 只返回已开奖场次**；非 `2` 时解析器**整体失败**（逐条跳过会让该场永久停在 PENDING）。
7. **`/nova/tf/odds` 一场一次调用**返回全部机构×玩法（`marketId=4` 让球、`5` 大小球）。让球 `line` = **主队让球数**（负=主让），同档两侧文本相同**不要取反**（The Odds API 相反，照搬 100% 判 `INCONSISTENT_SPREAD_PAIR`）。
8. **`/nova/tf/odds-history` 可回溯约 4 个月** → 历史赔率不用自己攒。`points[]` 升序 = **变动快照非定时采样**，取「≤T 最后一条」即 T 时刻精确价。
9. **`updateTime` 是北京时间不是 UTC**；生产 `NovaApiOddsPayloadMapper.parseTime` 按 UTC 解释——**回测做无泄漏断言必须按 `Asia/Shanghai`**，否则赛前 1h45m 显示成 9h45m，断言照样通过但时序全错。
10. **数据源赛前约 1h45m 停更** →「收盘价」实为 T-105min 价，别声称拿到临场价。
11. **多参数签名按字典序全签**（漏签 401 会列出参与验签的键）。接口文档内嵌 `assets/index-*.js`（`grep -bo '__name:"<id>"'` 取字节偏移），读法见 `docs/data-sources-novaapi.md`。

## 单源状态（阶段 0~5 全部完成，已合 master `9ab9d0d`）

- 阶段 4 验收 `SUCCESS 23/23`、次轮全 `unchanged`、13 条事实全 `FINAL/REGULATION_90/OFFICIAL`。**迁移最高 V27**（`backend/src/main/java/db/migration/` 有 Java 版 V22，新建 SQL 前先 `ls` 避撞号）。
- **V26 删映射层五张表**，已于 2026-10-25 由本地真实启动对生产库生效；删表前五张表 JDBC 备份在 `.workbuddy/tmp/mapping-backup-20261025-145650/`（`match_source_mappings` 45 / `provider_team_mappings` 82 / `provider_league_mappings` 10 / 两个 alias 0）。
- **V27 修 V25 两处自相矛盾**：① `score_scope` 声明 `VARCHAR(16)` 却 CHECK 允许 `INCLUDING_EXTRA_TIME`(20)/`INCLUDING_PENALTIES`(19) → 放宽到 24；② OFFICIAL 分支的 `provider_code` 白名单（`CHINA_SPORTTERY`/`NOVA_API`）原理不可强制（`provider_code` 是无域约束的自由文本），已回退到 V18 口径（只看 `data_type='SPORTTERY_RESULT'`，MANUAL 仍查 `MANUAL_ENTRY`）。`score_scope` **至今无 Java 写入方**，靠默认值停在 `REGULATION_90`。
- **落库是交叉积**（让球线 × 大小球线），冻结行要求同一行具备两个市场。必须同时改 V25 唯一键与 `AsianOddsSnapshotWriter` 去重维度（含 `total_line` + `IS NULL`），否则每轮整批重插且不报错。**大小球保留档按「跨庄去水概率离 50/50 的距离」取前 4，不按报出家数**（按家数会选稀疏整数盘、把主盘 `2.75/3.0` 排掉）。
- **`code != 200` 必须整体失败**：密钥过期放在响应体而 HTTP 仍 200；逐场降级会让同步显示成功、快照 0 条。单场 404 才降级，401/403 立即抛错。
- **读查询按 `provider_code` 过滤、不 JOIN 映射表**：去重键 `(bookmaker, line)` 不含 provider，混源同名庄会互相覆盖并一起进中位数。赔率响应**没有滚球标志**，只能用 `kickoff_time` 判赛前。`NovaApiClient` 装配条件是 OR，限速与赛程/赔率/赛果共用额度。
- **`matches` 必须写全 `league_id`/`home_team_id`/`away_team_id`**（联赛下拉由前端从 `leagueId !== null` 反推，不填会让筛选**静默消失**）。**赛果三约定**：`amended=true`、`providerUpdatedAt` 取信封 `timestamp`、`score_scope` 不主动写；窗口按开赛自然日 → 新库上线先补采前一天赛程。
- **生成是条件幂等**（赔率没变则同 `generationBatchHash`）；**亚盘同步是追加快照**，重跑后方向/置信度可能翻转（不是 bug）。单源后生成不再有「已确认映射」门槛：**有完整盘口的场次都会生成**（`MISSING_CONFIRMED_ASIAN_MARKET` 现语义 = 该场无任何亚盘快照）。
- **单源后没有映射层也没有旧源**：`SportteryProviderType` 只剩 `NOVA|STUB`、`AsianOddsProviderTypeEnum` 只剩 `STUB|NOVA_API`；两个 properties 记录只剩 `(provider, quotaWarningThreshold)`——`app.sporttery`/`app.asian-odds` 下的 base-url、api-key、超时、重试**从来没被读过**（Nova 只用 `app.nova`），别再往那儿加配置项。`app.sporttery.provider` 默认值已由 `china` 翻成 `nova`，`ProductionConfigurationTest` 钉住。
- **`LegacyNameKeyIdentityCleanupMigrationIT` 不受 V26 影响**：它显式 `flyway("22")` 收尾；仍在 `INSERT INTO provider_team_mappings` 是合法中间态。

## 本地实跑

- 探针 `tools/novaapi-probe/NovaApiProbe.java`（单文件 `java` 直跑），`raw <path> <query>` 打任意端点，产物落 `tools/novaapi-probe/novaapi-probe-out/<timestamp>/`。
- **本机 `mvn` 不在 PATH**：每次 export `JAVA_HOME=/c/D/java/tools/jdk/jdk-21.0.11+10`，`PATH` 加 `/c/D/java/tools/maven/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin`。`gh` 未安装 → 用 `api.github.com` + `Authorization: Bearer <classic PAT 带 repo+workflow>` 读日志/artifact（fine-grained token 需显式 Actions:Read，否则 403；CI 日志下载报 `Must have admin rights`）。
- 凭据走**环境变量不落盘**：应用侧 `NOVA_API_KEY`/`NOVA_PRIVATE_KEY`，探针用 `NOVA_PRIVATE_KEY_B64`。日卡会过期，需用户重发。
- **本地跑单源必须 `@SpringBootTest(properties=...)` 覆盖 provider**：`application-local.yml` 里是硬编码字符串，环境变量无效。全链路 `SportteryPoolSyncService.sync` → `AsianOddsSyncService.sync` → `PredictionAutoPublishOrchestrator.run(d)`。
- **`application-local.yml` 里 `app.tasks.enabled: true`，六个 Job 全开**——只想验证「能不能启动」必须命令行覆盖 `--app.tasks.enabled=false`，否则启动即真实同步 + 自动发布。
- **本地启动必须显式 `--server.port=8080 --management.server.port=8081`**：WorkBuddy 把自己占用的 55726 注入环境，`${SERVER_PORT:8080}` 会解析成 55726（`env` 里查不到，运行期确实拿到）。
- **Flyway 在 Web 服务器启动之前执行**：端口冲突导致 `APPLICATION FAILED TO START` 时迁移**已提交落库**。「本地起一下」本身就是一次数据库变更，破坏性迁移必须先备份。
- **`NoResourceFoundException` 被 `GlobalExceptionHandler` 当 500 返回**（应为 404）：猜错 API 路径会看到 `COMMON_INTERNAL_ERROR`。公开接口只有 `GET /api/public/matches`、`POST /api/public/matches/{list,detail}`、`POST /api/public/predictions/detail` 与快照下载；管理端没有 `dashboard` controller。
- **修复后线上形态（10-23 首批）**：8 条 `edge` 全 `0.000000`。两种正常「不发」：① 开赛后才同步赔率的场次丢弃；② 主盘档两侧 `1.90~1.98` 平衡 → 两市场都 LOW → **整场跳过**（12 场里 3 场）。**场场都有方向才是异常。** 导出盘口必须标侧别。
- **补算已开赛场次离线复刻、不写库**：`AsianMarketConsensusAggregator`/`AsianPublishGate`/`ImpliedPoissonModel` 无 Spring 依赖，直接 `new` + `backend/target/classes` 引用，**不要手写公式**（手写版已两次末位漂移）。

## 回测（原型已验证，未做成功能）

- `tools/hist-backtest/HistoryReplay.java`：odds-history 的 `points[]` 按「≤T 最后一条」切截面 → 伪装成一次同步的交叉积 → 交给生产同一批纯函数走完聚合/λ 反解/选冻结行/置信度。
- 用法：`java -cp "tools/hist-backtest/out;tools/hist-backtest/lib/*;backend/target/classes" HistoryReplay <json> "<kickoff>" "<T1,T2,...>"`。Jackson 三个 jar 需复制到 `lib/`。
- **已校验**：001（2042155）在 `10-23 15:14:21` 截面与实盘补算**逐位一致** → odds-history 可放心当特征源。
- **决策时点必须显式固定**：001 同一场 T-45h/T-24h/T-18h 让球主 MEDIUM、T-9h/T-6h LOW、T-3h 客 MEDIUM、收盘 LOW；大小球全程「大球 MEDIUM」。
- 底线：结果落 `backtest_*` 三表不进 `settlements`；复用生产纯函数；每次 run 记四版本号；无泄漏做成硬断言（`captured_at < kickoff_time`）。

## 已知待办

- **CI 已绿**（`6b8571a`：单测 716 + 集成 65 + 27 个迁移）。下一步重启上线链：`T106`/`T107` 两周连续观测 → `T108` Go/No-Go → `T604` Docker 部署 → `T605` MVP 验收。
- **V27 尚未对生产库应用**（生产仍是 v26），下次以 `local` profile 启动时 Flyway 会自动应用；它是加宽列 + 替换函数，无破坏性。
- 阶段 4 唯一欠证：**官方赛果 → 锁定 → 结算全链真跑**（目前结算均离线核对，未走 `SettlementService`）。
- 回测做成功能（原型已验证）。邻线校验口径（整条阶梯取最大 vs 主盘 ±0.75）不改代码，结论写进 `docs/asian-model-design.md`。
- `backend/src/test/java/com/jingcaicompass/tools/` 保持未跟踪（本地单测数比 CI 多 1~2）。
