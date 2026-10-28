# 竞彩罗盘 · 长期约定

只记「从代码里读不出来」的东西：跨模块的隐性规则、外部系统的实测事实、判断依据。实现细节看代码注释与 `docs/`。

## 用户偏好
- 个人自用，合规不作阻塞；务实优先，关键节点必须验证。中文、给结论而非罗列选项；说「发在会话里」就不写文件。
- **不凭记忆断言某能力不存在**：先查 `docs/data-sources-novaapi.md` 与 `admin/controller/`。

## 流程与验证
- 分支 `feat/<topic>`；同时只一个任务 `IN_PROGRESS`。迁移最高 **V29**（生产库已 v29，`db/migration/` 有 Java 版 V22，新建 SQL 先 `ls`）。
- **「本地能启动」≠「CI 能过」**：CI 是空 PG16 + V1~V29 全量重放 + `stub` provider + 65 个 IT 主动撞约束。无 Docker 就别加只在 CI 才暴露的约束。
- 纯计算→单测；**迁移 / PG 专有约束 / 触发器→必须真实 PG16 跑过**。`mvn -o test` 零 Docker（**857** 项）；`*IT.java` 仅 `-Pintegration`。前端门禁：`vitest run`（**110**）+ `tsc -b` + `vite build`。
- **`cmd | tail && echo OK` 判断不出失败**：管道退出码是 `tail` 的。必须 `cmd > log 2>&1; echo "exit=$?"`。
- **含明文库凭据的工具必须未跟踪或读环境变量**。**明文密码仍在本分支 `e260ca0`/`efb5953` 历史里，分支从未推送——合入前洗历史或轮换库密码。**
- 触发器 `RAISE ... USING CONSTRAINT='x'` 的约束名**不进 JDBC message**，断言只能比主消息文本。
- **`@Select` 不应用 `@TableField(typeHandler)`**（`autoResultMap` 的 resultMap 只被 MP 自己拼的 SQL 用）。**已踩两次**：归档 `payload`、`backtest_metrics` 两个 jsonb（详情页整页白屏）。特征：**写库正常、读回 null、不报错**。修法：`col::text`+Jackson 或 `selectList(wrapper)`。
- **`@ConditionalOnBean` 按同包字母序求值**：`...PoolBackfill...` 排在 `...ResultSync...` 前 → 扫描时 DataSource 未注册 → 启动即 `No qualifying bean`。要「无库也能装配」就**别加条件**，改 `ObjectProvider` 惰性取。

## 亚盘模型
- **方向与置信度有意不同源**：方向取模型概率；置信度按冻结行报价 `margin = 1/o1+1/o2-1`、`signal = |1/o1-1/o2|/total`（HIGH `≤.1000 且 ≥.0750`，MEDIUM `≤.1600 且 ≥.0250`）。**临场越平衡 signal 越小，方向明确却判 LOW 不是 bug。**
- **`edge ≡ 0`**：λ 由该主盘去水概率反解再投影回同一条线必然相等。真判据应是 `模型概率 − 1/赔率`；任何正 `safety-margin` 会拒发 100% 让球方向。
- **主盘残差不是自洽性检验**（恰定方程组，实测 `1e-16`）；判市场打架只看**邻线回投**（家数 ≥2 且非主盘线），`lineCount==0` 单打 `no-neighbor`。
- 主盘 = **偏离 50/50 最小的线**（非众数），`p_eff = (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)`；聚合 = **逐家去水 → 中位 → 归一**。**落库口径是首边**（主队/大球），`AsianPublishGate.Verdict` 里的概率已按方向做过 `1 − p`。
- **σ 真能拦但两种 source 不可比**：`>0.0200` 才拒、分市场独立；白名单达标时样本只剩两三家 → σ 系统性偏小。
- **首页主盘必须复用生成侧聚合器**：展示线必须等于决策并结算的那行（`DISTINCT ON ... captured_at DESC` 取到交叉积最后写入的行，与「最平衡」无关）。
- **决策出口 13 个、11 个枚举值**，顺序即语义。`INCONSISTENT_ASIAN_MARKETS` 有 `!feasible()` 与残差/邻线超限两个出口，**不能合并**。
- **λ 反解几乎从不失败**（7/8/9 月共 1021 场只有 5 场 `HANDICAP_PROBABILITY_OUT_OF_RANGE`），已落 `infeasibility_reason`。**残差超限永远为 0**——主盘两方程恰定，残差只有 1e-16。

## 存储与发布
- **哈希结构只能追加**（V4 = V3 + 十列）：全非空→v4、全空→v3、**部分为空抛错**。只发单边方向时四个 over 列为空 → 撞约束。**判 DRAFT 能否发布先查十列是否全填。**
- **`ck_predictions_publish_lifecycle` 要求四样同时写**且 `publish_time < lock_time`；唯一入口 `PredictionMapper.publishDraft`。触发器只允许 `DRAFT→PUBLISHED`，**历史 DRAFT 永远补不回来**。
- **新增落库列要改四处**：迁移列 + `protect_published_prediction()` 两处 ROW 列表 + `PredictionImportDto` 校验 + **`BacktestCaseMapper.insertIgnore` 的手写列清单**。第四处不报错，只让那列永远 null（已踩）。`BacktestCaseMapperSqlTest` 反射兜住。
- **批量写按 `(matchId, predictionId)` 升序串行 + 逐条独立事务**（反向拿锁会死锁）。
- **`CHAR(n)` 出库补空格**会让指纹查表静默 miss（指纹列一律 VARCHAR）。

## 统计口径
- **两种产品线不可混算**：响应看 `markets` 数组而非 `had`/`hhad`，按 `predictionType` 分别请求，首页固定亚盘。
- **走盘进 Brier、不进命中率分母**：`HIT 1 / HALF_WIN .75 / PUSH .5 / HALF_LOSS .25 / MISS 0`，`settled` 不含 PUSH。
- **历史复盘不要写 `settlements`**：用生产纯函数算（`AsianMarketTermsResolver` → `MarketSettlementCalculatorRouter` → `SettlementMarginSupport.netProfit`，后者包内私有）。按「实际发布的判定线」结算；**跨批次 ROI 绝不能直接汇总**。
- **批次结论只信同口径同批次**：10-21 `−6.17`；10-22 修复后 `+2.620`——**修复让账面回归真实，不是模型变准**；让球全中不算能力证据。

## NovaAPI
1. **`matchStartDate` 才是实际开赛日期**，`matchDate` 是竞彩业务日（13 场 10 场跨零点）。**赛果 `matchDate` 是开赛自然日** → 定位只能用 `matchId`（= `matches.external_match_id`）。
2. **`matchStatus` 是销售状态**（`Selling`/`SoldOut`/`Closed`）→ switch 必须处理 `SoldOut` 且带 `default` 抛错；`FINISHED` 只由赛果写入方设置。
3. **两套队名**：`allHomeTeam`/`allAwayTeam` 全称、`homeTeam`/`awayTeam` 简称；赛程 `homeTeamName` 对应全称，用简称会静默改写队名。`poolStatus` 跨接口同名不同义。
4. **字段类型会漂移**（`sellStatus` 是 `"2"`、`leagueId` 文档写 String 实为数字）→ 一律 `asText()`。**`code != 200` 必须整体失败**；单场 404 才降级，401/403 立即抛错。
5. **`sectionsNo999` = 竞彩 90 分钟比分（已实证）**：`matchResultStatus != 2` 即未开奖；**`ft + et` 会重复计入**。`/nova/text/match-results` 只返回已开奖场次，非 `2` 时解析器**整体失败**。
6. **`/nova/tf/odds` 一场一次调用**返回全部机构×玩法（`marketId=4` 让球、`5` 大小球）。让球 `line` = **主队让球数**（负=主让），同档两侧文本相同**不要取反**。响应**没有滚球标志**，只能用 `kickoff_time` 判赛前。
7. **回溯深度**：赛程/赛果 **≥16 个月**（`all-list?date=` 按单日拉，历史日也返回 `matchId`+队伍 ID）；**`odds-history` 仅约 4 个月**（`06-20` 起完整）——回测唯一硬约束。历史赔率 **6~7 家**（实时 10+），σ 不可互比。老场次 **20.8s**/388KB 逼近 `read-timeout 20s`。`points[]` 升序 = **变动快照非定时采样**，取「≤T 最后一条」即 T 时刻价。
8. **`updateTime` 是北京时间不是 UTC**；生产 `parseTime` 按 UTC 解释——**回测无泄漏断言必须按 `Asia/Shanghai`**。**赛前约 1h45m 停更** →「收盘价」实为 T-105min 价。
9. **多参数签名按字典序全签**（漏签 401 会列出参与验签的键）。接口文档内嵌 `assets/index-*.js`。
10. **`football-info` 的伤停对历史场次可用**：`GET /nova/sports-lottery/football-info?matchId=`。`injuries[]` 真实字段是 `playerName/teamType/position/isInjured/isSuspended`（**文档写的 `playerId` 不存在，按它计数会得到 0**）；`lineups` **历史场次不返回**。`feature`（主客场战绩+场均进失球）与 `result` 是**滚动 20 场且按场次时点生成**——同一队 7 月与 10 月数字不同、分母恒为 20，故无前视泄漏，可作特征。

## 单源与任务
- **落库是交叉积**（让球线 × 大小球线），冻结行要同一行具备两市场。必须同时改 V25 唯一键与 `AsianOddsSnapshotWriter` 去重维度（含 `total_line`+`IS NULL`）。**大小球保留档按「去水概率离 50/50 的距离」取前 4**。
- **读查询按 `provider_code` 过滤、不 JOIN 映射表**：去重键 `(bookmaker, line)` 不含 provider。`NovaApiClient` 装配条件是 OR，限速与赛程/赔率/赛果共用额度（**固定 2s/次**）。
- **`matches` 必须写全 `league_id`/`home_team_id`/`away_team_id`**（联赛下拉由 `leagueId !== null` 反推，不填会让筛选**静默消失**）。**赛果三约定**：`amended=true`、`providerUpdatedAt` 取信封 `timestamp`、`score_scope` 不主动写。
- **生成条件幂等**（赔率没变则同 `generationBatchHash`）；**亚盘同步追加快照**，重跑后方向/置信度可能翻转（不是 bug）。
- **按当日实体拼接的键都要算上界**：`request_key` 每场 8 字符，256 列宽 29 场溢出 → 加宽 1024 + 超 900 字符退化为「场次数 + SHA-256 前 16 字节」。
- **`ProviderSyncTemplate` 必须自兜底**：原只 catch `fetch()`，`savePayload`/`link` 在外 → 记录永久 `RUNNING`、额度显示 0。**见 `RUNNING`+`quota=0`+无 payload，先查写库异常。**
- **`@Scheduled` 无超时**，默认 `pool.size=1` 全部 Job 挤一个线程。已加 `SyncRunRecoveryService`（`RUNNING` 超 10min → `FAILED`）。
- **`initialDelayString` 不认 Spring Boot 简写时长**（`30s` 只对 `fixedDelayString` 有效），`"${...:60s}"` 会让应用 **`NumberFormatException` 起不来**。统一走 SpEL 毫秒。
- **自动发布没有「赛前 N 小时」窗口**：开赛后补的场次被 `KICKOFF_PASSED` 挡掉、补不回来。
- **没有映射层也没有旧源**：`app.sporttery`/`app.asian-odds` 下的 base-url、api-key、超时、重试**从没被读过**（Nova 只用 `app.nova`）。

## 本地实跑
- 探针 `tools/novaapi-probe/`；`.workbuddy/tmp/` 有临时 JDBC 探针（读 local 配置取凭据，控制台 GBK 乱码但数据可读）。
- **本机 `mvn` 不在 PATH**：`JAVA_HOME=/c/D/java/tools/jdk/jdk-21.0.11+10`，`PATH` 加 `/c/D/java/tools/apache-maven-3.9.16/bin`。`gh` 未装 → 网页建 PR。
- **管理端接口要 JWT**：`POST /api/admin/auth/login`（`admin` + `application-local.yml` 里 bootstrap 密码）→ `Authorization: Bearer`。**列表类接口是 POST 不是 GET**，GET 会返 500（`HttpRequestMethodNotSupportedException` 被当 500）。
- **`app.tasks.enabled: true` 不等于 Job 全开**：只显式开 5 个。要只跑回测：`--app.tasks.enabled=true --app.tasks.backtest-run.enabled=true` 且其余全 `false`。
- **本地启动必须显式 `--server.port=8080 --management.server.port=8081`**：WorkBuddy 注入的 55726 会被 `${SERVER_PORT:8080}` 取到。**Flyway 在 Web 启动之前执行**，端口冲突导致 `FAILED TO START` 时迁移已落库。
- **`NoResourceFoundException` 与 `HttpRequestMethodNotSupportedException` 都被当 500 返回**（应 404/405）——排查 URL/方法错误时别信状态码。
- **两种正常「不发」**：开赛后才同步赔率的场次丢弃；主盘两侧 `1.90~1.98` 平衡 → 整场跳过。**场场都有方向才是异常**。
- **补算已开赛场次离线复刻、不写库**：纯函数直接 `new` + `backend/target/classes`，**不要手写公式**（已两次末位漂移）。
- **验前端用 jsdom 渲染整页**：mock `fetch` + `MemoryRouter` 喂真实响应，能把「生产数据才触发的白屏」变成可复现断言（admin 页要登录态，截图验不了）。

## 回测
- 文档 `docs/backtest-design.md`（**§12 是缺陷台账**）；分支 `feat/historical-backtest`。
- 原型 `tools/hist-backtest/HistoryReplay.java`：`points[]` 按「≤T 最后一条」切截面 → 伪装交叉积 → 交生产同一批纯函数。**决策时点必须显式固定**，所以时点进了 `backtest_cases` 唯一键。
- **三条「同一份代码」**：判定 `AsianDecisionEngine`、结算 `MarketSettlementCalculatorRouter` + `netProfit`、指标 `StatisticsCalculator`。
- **无泄漏三道闸**：DB CHECK + **状态传 `SCHEDULED`**（库里是 `FINISHED`，直接传会被 `NOT_SCHEDULED` 吃掉 → 结果像「没样本」）+ 切片只取 `<=T`。
- **两套计数不能合并**（`total*` 展示、`batch*` 判终态）。标 SUCCEEDED 前强制校验无 `PENDING`。
- **`brierDelta = market − model`；但当前模型下 Δ ≡ 0**——**是恒等式不是「打平市场」**。市场 Brier 是 **0.1875 量级**；押次边要取 `1−p`。
- **归档三桶必须恰好划分 `processed`**（`reused+fetched+failed = processed`）。失败场次**不能**同时进 `fetched` 与 `failed`（复用10+新采40+失败1=51 撞 processed=50）。互斥收在 `ArchiveCounter.add()`。
- **归档必须自建 `odds_history_archive`**。**失败分四档**：`SUCCESS`/`NO_HISTORY`/`NO_QUOTE_YET` 可负缓存；`RETRYABLE_FAILURE` 必重采；`PERMANENT_FAILURE` 重采只重复错误。**HTTP 200 + 体里 `code=500`** 是上游偶发。
- **归档全新采约 5~6s/场**（限速 2s + 388KB 响应解析入库），复用几乎瞬时。**预估按 5s/场算**，按 2s 会低估一倍多。
- **候选只来自 `matches` 表**。`dateTo` 上限「昨天」，`dateFrom` 下限 **`今天−129天`**（每天前移）。
- **赛程回填**：`POST /api/admin/provider/sporttery/pool/backfill`（**单次 ≤7 天**，逐日 1 调用 = 1 额度）。**赛果回填**：`POST .../results/sync`（同样 ≤7 天，一直就有）。都走生产 sync 服务，幂等 upsert。
- **补完 7/8/9 月 = 196/418/424 场，1038 场共耗 92 次赛程额度**（10-27 实测，零失败）。补之前 7 月 12 行、8 月 36 行全部无外部 ID，9 月 0 行——**「没有可回测场次」是数据没进来，不是功能缺失**。
- **参数组只决定「发不发」，不决定「判什么」**：含义全在四个阈值（HIGH/MED 各一对：抽水上限 `margin` + 方向下限 `signal`）。**实测 160 条：tight 是 baseline 真子集，方向不一致 0 场**；收紧后 ROI 反而略差（−11.51→−12.98%）。
- **样本利用率低的主因是邻线容差 `0.04`，不是 λ 不可行**（旧结论已推翻）。7/8/9 月：决策 127/243/199，邻线超限 45/117/160，λ 真无解 0/0/5。被拒场次邻线偏差 min 0.0400~0.0409、p50 0.047~0.050，**八成分布在 0.04~0.06**。
- **放宽邻线容差会让 ROI 更差**（八月只改容差：0.04 → −0.89%，0.06 → −2.39%，反推新增 117 腿 ROI **−6.96%**）。所以邻线偏差是**模型不可信的度量**、不是「市场打架」的度量，这道闸门在过滤坏样本，**保持 0.04**。
- **「样本」是场次、「已结算」是腿**，`settled/case` 不是完成度。十月：81 场候选 → 80 有赔率 → 32 出方向 → 46 腿 → 40 腿已结算。ROI 分母 = 已结算腿数。
- **赛果整天缺口 = 「无效场次」**：腰斩/取消场的 `sectionsNo999` 写成「无效场次」→ 解析器抛 `PARSE_FAILURE` → **整份响应作废**，一个坏场次毁掉 7 天全部赛果（2040522/2040912/2041505/2041724）。绕法 = 拆单天；根治 = 记 `VOID`（枚举已有）而非抛错。
- **五个月 884 腿实测（10-27）**：6下/7/8/9/10 加权命中 51.4/53.1/50.5/47.8/42.7%，ROI +3.45/+4.24/−0.89/−3.50/−11.51%，**合并 49.9% / −0.87%**。**下滑大半是噪声**：仅 7 月 +2.0σ，10 月只 40 腿。**庄家数不是原因**（各月 6.0~6.9，6 月与 10 月同为 6.0）。让球与大小球走势不同。

## 前端约定
- **结论优先、推导折叠**：主行「主 -0.5 / 大 2.5」，λ/σ/残差收进 `<details>`。方向文案统一 `asianHandicapVerdictText`，亚盘线一律 `formatAsianLine`。
- **量纲不能借错函数**：λ（1.96）用 `formatProbability` 会渲染成「196.1%」；Brier 乘 100 会与命中率混成一个视觉量纲；样本为 0 的命中率显示「—」。
- **类型必须逐个对齐后端枚举**：`BacktestResultStatus` 曾写成 5 个值、后端只产生 3 个，**且没有编译报错**。可空字段要在类型上写 `| null`。
- **索引式访问外部数据一律先判空**：`Object.keys(null)` 抛 TypeError 被错误边界接住就是整页白屏。
- **检查项**：参数组固定三行（合计≠两市场相加）；默认区间取**最近 30 天**；`PARTIAL` 用警告色。
- **进度文案两段**：归档案看「N/M 场（%）」，判定及之后看「已产出 N 条样本」。状态名与阶段名同词时去重。

## 已知待办
- 阶段 4 唯一欠证：**官方赛果 → 锁定 → 结算全链真跑**；「锁定后首页 500」的 P0 本地从未触发。
- 「已发布预测开赛过后停在 PUBLISHED」仍在，需每天手动处理。
- 上线路线：`T106`/`T107` 观测两周 → `T108` Go/No-Go → `T604` Docker → `T605` MVP 验收。
- 回测待办：λ 不可行四种原因落库；`BacktestMigrationIT` 上 CI；给赛程/赛果回填做前端入口（目前只有接口）；「无效场次」改记 `VOID`。
