# 竞彩罗盘 · 长期约定

只记「从代码里读不出来」的东西：跨模块的隐性规则、外部系统的实测事实、判断依据。实现细节看代码注释与 `docs/`。

## 用户偏好
- 个人自用，合规不作阻塞；务实优先，关键节点必须验证。中文、给结论而非罗列选项。要求「发在会话里」就不写文件。
- **不凭记忆断言某能力不存在**：先查 `docs/data-sources-novaapi.md` 端点清单。

## 流程与验证
- 分支 `feat/<topic>`；同时只一个任务 `IN_PROGRESS`。迁移最高 **V29**（生产库已 v29，`db/migration/` 有 Java 版 V22，新建 SQL 先 `ls`）。
- **「本地能启动」≠「CI 能过」**：CI 是空 PG16 + V1~V29 全量重放 + `stub` provider + 65 个 IT 主动撞约束。声明矛盾类缺陷只在这里暴露；无 Docker 就别加只在 CI 才暴露的约束。
- 纯计算→单测；**迁移 / PG 专有约束 / 触发器→必须真实 PG16 跑过**。`mvn -o test` 零 Docker（**852** 项）；`*IT.java` 仅 `-Pintegration`。前端门禁：`vitest run`（**104**）+ `tsc -b` + `vite build`。
- **含明文库凭据的工具必须未跟踪或读环境变量**。**明文密码仍在本分支 `e260ca0`/`efb5953` 历史里，分支从未推送——合入前洗历史或轮换库密码。**
- 触发器 `RAISE ... USING CONSTRAINT='x'` 的约束名**不进 JDBC message**，断言只能比主消息文本。
- **`@Select` 不应用 `@TableField(typeHandler)`**（`autoResultMap` 生成的 resultMap 只被 MP 自己拼的 SQL 使用）。**已踩两次**：归档 `payload`（切片恒 0 行）、`backtest_metrics` 两个 jsonb 列（**详情页整页白屏**）。共同特征：**写库正常、读回 null、不报错**。修法：`col::text` + Jackson，或 default 方法里的 `selectList(wrapper)`。

## 亚盘模型
- **方向与置信度有意不同源**：方向取模型概率；置信度按冻结行报价 `margin = 1/o1+1/o2-1`、`signal = |1/o1-1/o2|/total`（HIGH `≤.1000 且 ≥.0750`，MEDIUM `≤.1600 且 ≥.0250`）。**临场越平衡 signal 越小，方向明确却判 LOW 不是 bug。**
- **`edge ≡ 0`**：λ 由该主盘去水概率反解再投影回同一条线必然相等。真判据应是 `模型概率 − 1/赔率`；任何正 `safety-margin` 会拒发 100% 让球方向。
- **主盘残差不是自洽性检验**（恰定方程组，实测 `1e-16`）；判市场打架只看**邻线回投**（家数 ≥2 且非主盘线），`lineCount==0` 单打 `no-neighbor`。
- 主盘 = **偏离 50/50 最小的线**（非众数），`p_eff = (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)`；聚合顺序 **逐家去水 → 中位 → 归一**。**落库口径是首边**（主队/大球），`AsianPublishGate.Verdict` 里的概率已按方向做过 `1 − p`。
- **σ 可真拦但两种 source 不可比**：`>0.0200` 才拒、分市场独立；白名单达标时样本掉到两三家 → σ 系统性偏小。
- **首页主盘必须复用生成侧聚合器**：展示线必须等于决策并结算的那行（`DISTINCT ON ... captured_at DESC` 取到交叉积最后写入的行，与「最平衡」无关）。
- **决策出口 13 个、11 个枚举值**，顺序即语义。`INCONSISTENT_ASIAN_MARKETS` 有 `!feasible()` 与残差/邻线超限两个出口，**不能合并**——合并后分布分不开「λ 解不出来」和「市场打架」。
- **λ 不可行有四种**（`ImpliedPoissonModel`），**原因没落库**，是样本利用率低的根因却查不到细分。

## 存储与发布
- **哈希结构只能追加**（V4 = V3 + 十列）：全非空→v4、全空→v3、**部分为空抛错**。只发单边方向时四个 over 列为空 → 撞约束。**判 DRAFT 能否发布先查十列是否全填。**
- **`ck_predictions_publish_lifecycle` 要求四样同时写**且 `publish_time < lock_time`；唯一入口 `PredictionMapper.publishDraft`。触发器只允许 `DRAFT→PUBLISHED`，**历史 DRAFT 永远补不回来**。
- **新增落库列必须同时改三处**：迁移列 + `protect_published_prediction()` 两处 ROW 列表 + `PredictionImportDto` 校验；精度 `numeric(9,6)`，加列前确认该值在所有分支都被计算。
- **批量写按 `(matchId, predictionId)` 升序串行 + 逐条独立事务**（反向拿锁会死锁）。
- **`CHAR(n)` 出库补空格**会让指纹查表静默 miss（指纹列一律 VARCHAR）。静态断言要用 `(?<!VAR)CHAR\(`，`doesNotContain("CHAR(32)")` 会被 `VARCHAR(32)` 命中。

## 统计口径
- **两种产品线不可混算**：响应看 `markets` 数组而非 `had`/`hhad`，按 `predictionType` 分别请求，首页固定亚盘。
- **走盘进 Brier、不进命中率分母**：`HIT 1 / HALF_WIN .75 / PUSH .5 / HALF_LOSS .25 / MISS 0`，`settled` 不含 PUSH。
- **历史复盘不要写 `settlements`**：用生产纯函数算（`AsianMarketTermsResolver` → `MarketSettlementCalculatorRouter` → `SettlementMarginSupport.netProfit`，后者**包内私有**）。按「实际发布的判定线」结算；**跨批次 ROI 绝不能直接汇总**。
- **批次结论只信同口径同批次**：10-21 `−6.17`；10-22 修复后 `+2.620`——**修复让账面回归真实，不是模型变准**；让球全中不算能力证据。

## NovaAPI
1. **`matchStartDate` 才是实际开赛日期**，`matchDate` 是竞彩业务日（13 场 10 场跨零点）。**赛果 `matchDate` 是开赛自然日** → 定位只能用 `matchId`（= `matches.external_match_id`）。
2. **`matchStatus` 是销售状态**（`Selling`/`SoldOut`/`Closed`）→ switch 必须处理 `SoldOut` 且带 `default` 抛错；`FINISHED` 只由赛果写入方设置。
3. **两套队名**：`allHomeTeam`/`allAwayTeam` 全称、`homeTeam`/`awayTeam` 简称；赛程 `homeTeamName` 对应全称，用简称会静默改写队名。`poolStatus` 跨接口同名不同义，用它过滤赛果会漏单。
4. **字段类型会漂移**（`sellStatus` 是 `"2"`、`leagueId` 文档写 String 实为数字）→ 一律 `asText()`。**`code != 200` 必须整体失败**（密钥过期放响应体而 HTTP 仍 200）；单场 404 才降级，401/403 立即抛错。
5. **`sectionsNo999` = 竞彩 90 分钟比分（已实证）**：`matchResultStatus != 2` 即未开奖；**`ft + et` 会重复计入**。`/nova/text/match-results` 只返回已开奖场次，非 `2` 时解析器**整体失败**（逐条跳过会让该场永久 PENDING）。
6. **`/nova/tf/odds` 一场一次调用**返回全部机构×玩法（`marketId=4` 让球、`5` 大小球）。让球 `line` = **主队让球数**（负=主让），同档两侧文本相同**不要取反**。赔率响应**没有滚球标志**，只能用 `kickoff_time` 判赛前。
7. **回溯深度（10-25 实测）**：赛程/赛果 **≥16 个月**；**`odds-history` 仅约 4 个月**（`06-20` 起完整），是回测唯一硬约束。历史赔率仅 **7~8 家**（实时 10+），σ 不可与线上阈值互比。老场次 **20.8s**/388KB 逼近 `read-timeout 20s`。`points[]` 升序 = **变动快照非定时采样**，取「≤T 最后一条」即 T 时刻精确价。
8. **`updateTime` 是北京时间不是 UTC**；生产 `parseTime` 按 UTC 解释——**回测无泄漏断言必须按 `Asia/Shanghai`**，否则赛前 1h45m 变 9h45m，断言照样通过但时序全错。**赛前约 1h45m 停更** →「收盘价」实为 T-105min 价。
9. **多参数签名按字典序全签**（漏签 401 会列出参与验签的键）。接口文档内嵌 `assets/index-*.js`。

## 单源与任务
- **落库是交叉积**（让球线 × 大小球线），冻结行要求同一行具备两个市场。必须同时改 V25 唯一键与 `AsianOddsSnapshotWriter` 去重维度（含 `total_line` + `IS NULL`），否则每轮整批重插且不报错。**大小球保留档按「去水概率离 50/50 的距离」取前 4，不按报出家数**。
- **读查询按 `provider_code` 过滤、不 JOIN 映射表**：去重键 `(bookmaker, line)` 不含 provider。`NovaApiClient` 装配条件是 OR，限速与赛程/赔率/赛果共用额度。
- **`matches` 必须写全 `league_id`/`home_team_id`/`away_team_id`**（联赛下拉由 `leagueId !== null` 反推，不填会让筛选**静默消失**）。**赛果三约定**：`amended=true`、`providerUpdatedAt` 取信封 `timestamp`、`score_scope` 不主动写。
- **生成条件幂等**（赔率没变则同 `generationBatchHash`）；**亚盘同步追加快照**，重跑后方向/置信度可能翻转（不是 bug）。
- **按当日实体拼接的键都要算上界**：`request_key` 每场 8 字符，256 列宽 29 场溢出 → 加宽 1024 + 超 900 字符退化为「场次数 + SHA-256 前 16 字节」。
- **`ProviderSyncTemplate` 必须自兜底**：原只 catch `fetcher.fetch()`，`savePayload`/`link` 在外 → 记录永久 `RUNNING`、额度显示 0。**见 `RUNNING` + `quota=0` + 无 payload，先查写库异常。**
- **`@Scheduled` 没有任何超时**，默认 `pool.size=1` 全部 Job 挤一个线程。已加 `SyncRunRecoveryService`（`started_at < now-10min` 的 `RUNNING` → `FAILED`）。
- **`initialDelayString` 不认 Spring Boot 简写时长**（`30s` 只对 `fixedDelayString` 有效），`"${...:60s}"` 会让应用 **`NumberFormatException` 起不来**，而该 bean 常带 `@ConditionalOnProperty`，默认配置永远发现不了。统一走 SpEL 毫秒。
- **`@ConditionalOnBean` 对配置类里的 `@Bean` 在组件扫描阶段恒 false**：`PredictionAutoPublishJob` 曾因此**从未创建过**（零日志）。**判据：actuator 无 `jingcai.job.execution` = 一个 job 都没记过账。**
- **自动发布没有「赛前 N 小时」窗口**：开赛后才补的场次被 `KICKOFF_PASSED` 挡掉、补不回来。
- **没有映射层也没有旧源**：`app.sporttery`/`app.asian-odds` 下的 base-url、api-key、超时、重试**从来没被读过**（Nova 只用 `app.nova`）。

## 本地实跑
- 探针 `tools/novaapi-probe/NovaApiProbe.java`。凭据走 `NOVA_API_KEY`/`NOVA_PRIVATE_KEY`；日卡会过期。
- **本机 `mvn` 不在 PATH**：`JAVA_HOME=/c/D/java/tools/jdk/jdk-21.0.11+10`，`PATH` 加 `/c/D/java/tools/maven/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin`。`gh` 未装 → 网页建 PR。
- **本地跑单源必须 `@SpringBootTest(properties=...)` 覆盖 provider**（`application-local.yml` 是硬编码字符串）。
- **`app.tasks.enabled: true` 不等于 Job 全开**：只显式开 5 个，`asian-odds`/`data-pipeline`/`snapshot-publish` 默认 `false`、bean 不创建。要只跑回测：`--app.tasks.enabled=true --app.tasks.backtest-run.enabled=true` 且其余全 `false`（否则会写生产库并烧上游额度）。
- **本地启动必须显式 `--server.port=8080 --management.server.port=8081`**：WorkBuddy 注入的 55726 会被 `${SERVER_PORT:8080}` 取到。**Flyway 在 Web 启动之前执行**，端口冲突导致 `FAILED TO START` 时迁移已落库。
- **`NoResourceFoundException` 与 `HttpRequestMethodNotSupportedException` 都被 `GlobalExceptionHandler` 当 500 返回**（应 404/405）——排查 URL/方法错误时别信状态码。回测管理端 `cases` 是 **POST**。
- **两种正常「不发」**：开赛后才同步赔率的场次丢弃；主盘两侧 `1.90~1.98` 平衡 → 整场跳过。**场场都有方向才是异常**。
- **补算已开赛场次离线复刻、不写库**：纯函数直接 `new` + `backend/target/classes`，**不要手写公式**（手写版已两次末位漂移）。
- **验前端优先用 jsdom 渲染整页**：mock `fetch` + `MemoryRouter` 喂真实响应，能把「生产数据才触发的白屏」变成可复现断言（admin 页要登录态，截图验不了）。

## 回测（P1~P6 完成，真实库跑过 10 场与 81 场）
- 文档 `docs/backtest-design.md`（**§12 是缺陷台账**）；分支 `feat/historical-backtest`。提交：P4 `efb5953`、P5 `1bc2eaf`、P6 `da77588`、三桶修复 `43e3ab1`、白屏 `76e383a`。
- 原型 `tools/hist-backtest/HistoryReplay.java`：`points[]` 按「≤T 最后一条」切截面 → 伪装交叉积 → 交生产同一批纯函数，**已校验逐位一致**。**决策时点必须显式固定**，所以时点进了 `backtest_cases` 唯一键。
- **三条「同一份代码」**：判定 `AsianDecisionEngine`、结算 `MarketSettlementCalculatorRouter` + `netProfit`、指标 `StatisticsCalculator`。复制公式 = 改四分盘规则时有一边静默不同步。
- **无泄漏三道闸**：DB CHECK + **状态传 `SCHEDULED`**（库里是 `FINISHED`，直接传会被 `NOT_SCHEDULED` 吃掉 → 结果像「没样本」）+ 切片只取 `<=T`。
- **两套计数不能合并**（`total*` 展示、`batch*` 判终态，合并会让中途全失败被后续成功掩盖）。标 SUCCEEDED 前强制校验无 `PENDING`。
- **`brierDelta = market − model`；但当前模型下 Δ ≡ 0**（λ 反解再投影回同一条线）——**是恒等式不是「打平市场」**。市场 Brier 是 **0.1875 量级**；押次边要取 `1−p`。
- **归档三桶必须恰好划分 `processed`**（`reused+fetched+failed = processed`）。失败场次**不能**同时进 `fetched` 与 `failed`（复用10+新采40+失败1=51 撞 processed=50，run 变 FAILED 像归档崩了）。**只在真有补采失败时暴露**。互斥收在 `ArchiveCounter.add()`。
- **归档必须自建 `odds_history_archive`**。**失败分四档**：`SUCCESS`/`NO_HISTORY`/`NO_QUOTE_YET` 可负缓存；`RETRYABLE_FAILURE` 必重采；`PERMANENT_FAILURE` 重采只重复错误。**HTTP 200 + 体里 `code=500`** 是上游偶发。
- **候选只来自 `matches` 表，回溯范围 = 赛程采集范围**（本库只有 2026-10 的 91 场可用，7/8 月 48 行无外部 ID，9 月 0 行）。**管理端没有「按日期段同步赛程」的入口**，所以 6~9 月跑不了。`dateTo` 上限是「昨天」。
- **样本利用率约 40%，主因是 λ 不可行**：十月 80 场 → `INFEASIBLE` 44 / `DECIDED` 32；44 场**全部**记成 `INCONSISTENT_ASIAN_MARKETS`，**没有一场是真的残差或邻线超限** → 看分布会误以为「市场在打架」。
- **「样本」是场次、「已结算」是腿，两者分母不同，`settled/case` 不是完成度**。十月路径：81 场候选 → 80 场有赔率 → 32 场出方向 → 46 腿 → 40 腿已结算（完成度 87%）。ROI 分母 = 已结算腿数（`-4.605/40 = -11.51%`）。
- **赛果同步有整天缺口，且无回填入口**（10-22 全 13 场、10-26 全 4 场均无赛果，`match_status` 停在 `SCHEDULED`）。**不是「跨零点」本身的问题**——10-20 那批同样跨零点却全有。缺「按日期段回填」这一能力，与赛程缺失同源，一个入口能一起解决。
- **口径版本号四个**：不 bump 不是报错，而是**两份结论看起来同源**。`selector` 排序第一键是对齐大小球主盘。
- **`RestClient` 无 per-call 读超时**（工厂级）→ 历史 60s 与实时 20s 只能拆两个 bean。
- **持久化服务只加 `@ConditionalOnBean(DataSource)` 不够**：必须 `PersistenceServicesAutoConfiguration` 补 `@Bean`；controller 用 `ObjectProvider` 惰性取。
- **未做**：`BacktestMigrationIT` 未真跑（无 Docker）。`backtest_cases` 有 DELETE 守卫，跑废的 run 删不掉也不该删。

## 前端约定
- **结论优先、推导折叠**：主行是「主 -0.5 / 大 2.5」，λ/σ/残差收进 `<details>`。方向文案统一 `asianHandicapVerdictText`（平手盘写「平手」），亚盘线一律 `formatAsianLine`。
- **量纲不能借错函数**：λ（期望进球 1.96）用 `formatProbability` 会渲染成「196.1%」；Brier 乘 100 会和命中率混成一个视觉量纲；样本为 0 的命中率显示「—」。
- **类型必须逐个对齐后端枚举**：`BacktestResultStatus` 曾写成 5 个值、后端只产生 3 个，**且没有编译报错**。可空字段（如 `skipReasonCounts`）要在类型上就写 `| null`。
- **索引式访问外部数据一律先判空**：`Object.keys(null)` 抛 TypeError 被错误边界接住就是整页白屏。
- **检查项**：参数组固定三行（合计≠两市场相加）；默认区间取**最近 30 天**（回溯起点到今天 = 129 天超上限，预估按钮直接灰）；`PARTIAL` 用警告色非成功色；antd 两字按钮自动插空格（「续 跑」）。
- **进度文案两段**：归档案看「N/M 场（%）」，判定及之后看「已产出 N 条样本」。状态名与阶段名同词时去重。
- **心跳不能只在「有数字可报」时发**：全零增量直接 return 会让整批被跳过的 run 停心跳，重启自愈判成 `INTERRUPTED`。

## 已知待办
- 阶段 4 唯一欠证：**官方赛果 → 锁定 → 结算全链真跑**；「锁定后首页 500」的 P0 本地从未触发，上线前必须确认。
- 「已发布预测开赛过后停在 PUBLISHED」仍在，需每天手动处理。
- 上线路线：`T106`/`T107` 两周观测 → `T108` Go/No-Go → `T604` Docker 部署 → `T605` MVP 验收。
- 回测待办：**按日期段回填赛程与赛果的入口**（同时解决 6~9 月缺赛程、10-22/10-26 缺赛果）；把 λ 不可行的四种原因落库；`BacktestMigrationIT` 上 CI。
