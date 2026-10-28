# 历史回测：设计与改造方案

> 状态：P1~P6 已实现；真实库上跑过 10 场（链路验证）与 81 场（十月全月）
> 版本：0.3 · 2026-10-27
> 范围：在既有 `backend` 上增量建设，不新建工程、不改动生产预测链路
> 相关：`docs/asian-model-design.md`（模型与阈值来源）、`docs/data-sources-novaapi.md`（历史赔率接口）、`backend/src/main/resources/db/migration/V29__add_historical_backtest.sql`

---

## 1. 这个功能回答什么问题

一句话：**在历史价格上，这套阈值到底赚不赚钱；换一组阈值会不会更好。**

它不回答两件事：

- **不回答「模型准不准」。** 判定逻辑、结算逻辑、指标算法全部调用生产同一批纯函数，
  回测与线上跑的是同一份代码。所以回测结论不能用来验证代码正确性——它验证的是
  **阈值选择**与**市场环境**。
- **不回答「未来会怎样」。** 回测样本必然偏小（见 §2），结论只能写成「这批样本上
  A 组优于 B 组」，不能写成「A 组 ROI 是 4%」。

它的用途是**参数组之间的横向比较**，而且只在同一批样本、同一套口径版本内可比（§7）。

---

## 2. 唯一硬约束：历史赔率只有约 4 个月

这是整个功能的设计前提，也是它天然的样本上限。实测（2026-10-25）：

| 回溯深度 | 结论 |
|---|---|
| 赛程、赛果 | ≥ 16 个月，可正常取 |
| **`odds-history`** | **仅约 4 个月** |

`odds-history` 的退化是渐进的，不能按「有没有 200」判断可用性：

- `06-10` → **404**，完全没有
- `06-15` → 200，但整场只剩 **1 个变动点**（无法反解 λ）
- `06-20` 起 → 200 且变动点完整

因此 `app.backtest.limits.min-lottery-date` 默认 `2026-06-20`，且前端把这个下界
直接写进日期输入框的 `min`，并在越界时说明原因——**不是「日期格式不对」，是「再早
就没有报价」**。

另外两条会影响解读的限制：

1. **赛前约 1h45m 停止更新。** 所谓「收盘价」实际上是 T−105min 的价。决策时点设得
   比 105 分钟更近不会拿到更临场的价，只会拿到同一份旧数据。默认
   `decision-lead-minutes = 105` 正是这个原因。
2. **历史报价家数更少（7~8 家，实时 10+）。** 跨庄分歧 σ 会系统性偏小，
   **回测里的 σ 不可与线上阈值直接互比**。这条必须写在结论旁边，否则会得出
   「历史上市场更一致」的错误推论。

---

## 3. 三条「同一份代码」原则

回测最大的风险不是算错，而是**用一份看起来一样的复刻实现算出不一样的结果，而所有
断言照样通过**。所以三处都直接调生产代码：

| 环节 | 生产实现 | 回测如何复用 |
|---|---|---|
| 判定 | `AsianMarketConsensusAggregator` / `AsianFreezeSnapshotSelector` / `AsianDecisionEngine` | 直接 `new`，不做任何复刻 |
| 结算 | `MarketSettlementCalculatorRouter` / `SettlementMarginSupport.netProfit` | 只把 `final class` 与 `static` 提为 `public`，公式一行未改 |
| 指标 | `StatisticsCalculator` | 把样本包成 `HistoryListItemVo` 后直接喂进去 |

第二条曾经差点走偏：`SettlementMarginSupport` 是包内私有，最省事的做法是复制一份
`netProfit` 到回测包里。**不能这么做**——四分盘（2.75 这种线）半赢半输的折算规则
一旦调整，复制品会静默停在旧口径，而两边都有测试、都通过。

`AsianDecisionEngine` 在 P1 就被从 service 里抽出来，原因也在这里：它原来长在
`BaselinePredictionGenerationServiceImpl` 里，回测要用就得复制。

---

## 4. 无泄漏：三道闸

「无泄漏」指**决策时点 T 之后的信息不得进入 T 时刻的判定**。单靠代码自觉不可靠，
所以做成三道可以独立失败的闸：

1. **DB CHECK。** `backtest_cases` 上 `decision_at < kickoff_time`、
   `frozen_captured_at <= decision_at`。写错时点的样本直接写不进去。
2. **状态传参。** 判定时把比赛状态传成 `SCHEDULED`。库里存的是 `FINISHED`
   （赛果已回写），直接传真实状态会被引擎的 `NOT_SCHEDULED` 出口吃掉——
   这场就不判了，而不是判错——但结果同样是「没有样本」，非常难发现。
3. **切片器只取 `≤T` 的点。** `odds-history` 返回的 `points[]` 是**变动快照**
   （不是定时采样），按升序排列，取「≤T 的最后一条」就是 T 时刻的精确价。

第 3 条有一个时区陷阱：接口的 `updateTime` 是**北京时间**，而生产
`NovaApiOddsPayloadMapper.parseTime` 按 UTC 解释。回测做无泄漏断言必须显式按
`Asia/Shanghai` 解析，否则赛前 1h45m 会被算成 9h45m，**断言照样通过但时序全错**。

---

## 5. 归档表为什么不复用 `raw_data_payloads`

两者看着都是「存原始响应」，但生命周期与粒度完全不同：

| | `raw_data_payloads` | `odds_history_archive` |
|---|---|---|
| 存什么 | 一次同步调用的原始响应（当前态） | 一场比赛的全史 + 按 T 切好的截面 |
| 按什么计 | 按 run | 按 `match_id` |
| 能否复用 | 不能：同一场会被不同 run 反复覆盖 | 能：命中即跳过，省额度 |
| 状态机 | 无 | 需终态判定与增量补采 |

`raw_data_payloads.data_type` 还有 CHECK 白名单，塞历史序列进去要么改白名单、
要么伪装成别的类型。所以 V29 自建一张表。

自建表带来一个新问题：**定长键不能用 `CHAR(n)`**。PG 的 `CHAR(n)` 出库会补空格，
而 `payload_hash` / `run_key` / `group_key` 都要与内存里的十六进制指纹逐字比对——
读回带空格会让查表静默 miss，表现为「明明有归档却判定未采过」。V29 里这三列
一律 `VARCHAR`。

---

## 6. 运行编排

### 6.1 状态机

```
QUEUED → ARCHIVING → EVALUATING → SETTLING → AGGREGATING → SUCCEEDED | PARTIAL
                                                          ↘ FAILED
   ↑                                                        ↘ CANCELLED
   └── INTERRUPTED（唯一可回退）
```

- **终态不可再改**，由触发器与 `settleOnce` 式的条件更新共同保证。
- **标 `SUCCEEDED` 前必须校验无 `PENDING` 样本**。`PARTIAL` 表示「样本可用但补采有
  失败」，它对外可见（公开端白名单含它），所以必须在 UI 上同时显示失败场次数。

### 6.2 抢单

`FOR UPDATE SKIP LOCKED` 放在**短事务**里，状态推进同事务提交。这样「谁在跑」
是库里的 `run_status` 与 `started_at`，而不是靠心跳去猜。

心跳超时的 `RUNNING` 由 `BacktestRunRecoveryRunner`（`ApplicationRunner`）在启动时
恢复为 `FAILED`，判据与生产同步任务一致：`started_at < now - 10min`。

### 6.3 两套计数不能合并

归档与判定各维护 `total*` 与 `batch*`：

- `total*` 用于展示进度（跨批次累计）
- `batch*` 用于**判断终态**

合并成一套会让「中途某批全失败」被后续成功批次掩盖成 `SUCCEEDED`。

### 6.4 job 不用 `@Component`

`BacktestRunJob` 刻意只标 `@Scheduled`，由
`PersistenceServicesAutoConfiguration` 用 `@Bean` 创建。原因：`@ConditionalOnBean`
在组件扫描阶段对被注解类恒为 `false`，类级注解会让 bean 不创建且零日志——
`PredictionAutoPublishJob` 就是这样从未被创建过（10-23 起无新预测）。

---

## 7. 指标口径

### 7.1 三个 scope 各自独立

`ALL` / `ASIAN_HANDICAP` / `ASIAN_TOTAL_GOALS` **各算一次**。
`ALL` 不是两个亚盘市场相加：两个市场的注数不相等，也没有「一个总命中率」对应的
可下注对象。

### 7.2 走盘进 Brier、不进命中率分母

| 状态 | 记账值 |
|---|---|
| `HIT` | 1 |
| `HALF_WIN` | 0.75 |
| `PUSH` | 0.5 |
| `HALF_LOSS` | 0.25 |
| `MISS` | 0 |

- **命中率**：分母不含 `PUSH`，半赢半输按命中计。
- **加权命中率与 Brier**：走盘记 0.5 参与计算。

这条口径有一个反直觉的后果：市场 Brier **不是 0.25 而恒为 0.5**（当市场概率恒为
0.5 1−p 时），因为走盘记 0.5 恰好在这一腿拿满分。算错两次才对上：

- 第一次漏了「押次边时取 `1−p`」，模型 Brier 算成了 0.45²。
- 第二次把市场 Brier 当成 0.25。

### 7.3 `brierDelta = market − model`，正数才是模型更好

负值是**合法结论**，表示这批样本上模型不如市场。不取绝对值、不隐藏、不显示成持平。
取绝对值是最容易骗过自己的一种美化。

展示上刻意不给 Δ 着色：这一页已经用「正红负绿」表示收益，再用红色表示「不如市场」
会让同一个红有两种相反含义。所以 Δ 只给文字判断（优于市场 / 不如市场 / 持平）。

**但这个 Δ 在当前模型下恒为 0。** 原因与 `asian-model-design.md` 里记的 `edge ≡ 0`
是同一件事：模型概率是由该主盘的去水概率反解 λ，再投影回**同一条线**，因此与市场概率
逐位相等，两侧 Brier 必然相同。它不是「模型打平市场」，而是恒等式。

实测（`2026-10-20` 一天、两个参数组、6 行指标）Δ 全部为 `0.0000`。所以：

- **Δ 列不构成「模型优于市场」的证据**，只在换了模型结构（不再是主盘反解投影）之后才有信息量。
- 参数组之间的比较主要看 **ROI 与命中率**；Brier 的绝对值只能反映「模型自我一致性」，
  在同一模型的不同阈值下差异很小。

这条不写清楚，读的人会把「Δ = 0」当成 bug 去查，或者反过来把它当成「模型与市场等价」的结论。

### 7.4 选优顺序

ROI 降序 → Brier 升序 → `settled` 降序。**判定理由必须一起返回并显示**，
不写理由的「最优」既无法反驳也无法复核。

---

## 8. 口径版本号

`backtest_runs` 记四个版本号，任何一个变了，同名的两份结论就不可比：

| 列 | 常量 | 何时 bump |
|---|---|---|
| `aggregation_version` | `AsianMarketConsensusAggregator.AGGREGATION_VERSION` | 聚合顺序（逐家去水 → 中位 → 归一）调整 |
| `selector_version` | `AsianFreezeSnapshotSelector.SELECTOR_VERSION` | 主盘定义或冻结行排序键调整 |
| `model_version` | 生产模型版本 | 阈值或模型结构变化 |
| `builder_version` | `BacktestCaseBuilder` 内的常量 | 样本落库口径变化 |

`selector_version` 尤其重要：冻结行排序键的**第一键是对齐大小球主盘**，那是
2026-10-22「13/13 全小球」事故的根因修复。回测若用一份不含该键的实现，选出来的
冻结行与线上不同，而所有断言照样通过。

不 bump 版本号的后果不是报错，而是**两份结论看起来同源**。

---

## 9. 数据模型（V29）

| 表 | 粒度 | 关键约束 |
|---|---|---|
| `odds_history_archive` | 一场一序列 + 切片 | `uk(match_id, provider_code)`、`payload_hash` 正则 |
| `backtest_runs` | 一次回测 | `uk(run_key)`、状态机触发器 |
| `backtest_cases` | 场次 × 参数组 × 决策时点 | `uk(run_id, external_match_id, group_key, decision_at)` |
| `backtest_metrics` | 参数组 × 口径 | `uk(run_id, group_key, market_scope)` |

`group_key` 进唯一键是为了**断点续跑幂等**：续跑时会重算同一批场次，靠唯一键
`ON CONFLICT DO NOTHING` 保证不重复写样本。

结算结果**不进 `settlements` 表**。那是生产表的语义（一次真实发布的结算），
回测结果混进去会让「已结算预测数」这类统计静默变多。

---

## 10. 失败归因：四类，不能合并

归档的失败必须分开记，因为它们的重试策略相反：

| 结果 | 含义 | 可复用 | 重试 |
|---|---|---|---|
| `SUCCESS` | 取到完整序列 | 是 | — |
| `NO_HISTORY` | 404 | 是（负缓存） | 不必 |
| `NO_QUOTE_YET` | 200 但空序列 | 是（负缓存） | 不必 |
| `RETRYABLE_FAILURE` | 5xx / 超时 | 否 | 必重采 |
| `PERMANENT_FAILURE` | 400 / 401 / 403 | 否 | 重采只会重复同一错误 |

一条实测特例：**HTTP 200 + 响应体 `code=500`** 是上游偶发（换 `idEvent` 即 200）。
`ProviderHttpExecutor` 看不到响应体，只能由客户端自己再试一次。

`code != 200` 必须整体失败——密钥过期时错误放在响应体里而 HTTP 仍是 200。

---

## 11. 怎么跑一次

1. 后台「历史回测」→ 填名称、日期区间、决策时点、联赛范围、参数组。
2. 点「预估成本」：场次数、可复用归档数、预计额度、预计耗时。
3. 超过额度确认阈值（默认 4000）时，必须勾选确认才能创建——预估先于付费。
4. 创建后由 worker 抢单推进，详情页 3s 轮询到终态自行停止。
5. 终态后看「参数组指标」：每个组三行（合计/让球/大小球），
   最优组附判定理由；逐场明细可下钻到 λ、σ、残差与市场概率。

`app.tasks.backtest-run.enabled` 默认 `false`，需要在要跑的 profile 里显式打开。

---

## 12. 已知边界与未做

- **整链已在真实库跑通**（2026-10-26）：`create → ARCHIVING → EVALUATING → SETTLING →
  AGGREGATING → SUCCEEDED`，10 场 × 2 参数组 → 20 条样本，归档复用 10/10、额度 0。
  结果：`tight` 让球 4 腿 66.67% / ROI +29%，大小球 2 腿 0% / ROI −75%；
  `baseline` 12 腿 40% / ROI −13.17%。**样本只有 10 场，这个数字没有任何统计意义**，
  它的价值是证明链路能算对、能算出东西。
- **首跑暴露了三个缺陷**（都由「只在默认关闭的开关打开时才创建」或「只在真实库才有数据」
  才触发，单测与 CI 覆盖不到）：
  1. `initialDelayString = "${...:60s}"` → `NumberFormatException`，应用起不来。
     `initialDelayString` 不认 Spring Boot 的简写时长，`fixedDelayString` 认——两条解析路径。
     现在两个延迟都走 `SyncTaskSchedule` 同款的 SpEL 毫秒。
  2. 归档与判定**各自**累加 `processed_match_count` → 10 场变 20 场，撞
     `ck_backtest_runs_counts`。现在 processed 只由归档阶段推进，判定阶段的 flush
     方法签名里根本没有这个参数（写错都写不出来）。
  3. `@Select` 注解查询**不应用** `@TableField(typeHandler)`，`payload` 这个
     `Map<String,Object>` 字段读回来是空 → 切片恒 0 行、切场全 `MISSING_ROWS`，
     而归档状态、点数、庄家数全部正常。现在改用 `SELECT payload::text` 再 Jackson 解析。
- **同一类坑踩了第二次，这次代价是整页白屏。** 归档的 `payload` 修好之后，
  `backtest_metrics` 的 `skip_reason_counts` / `metrics_json` 走的还是
  `@Select ... SELECT *`，于是两列读回 `null`——而写库是正常的（MP 的 insert 会用
  typeHandler），所以库里一直有值、接口一直返回 `null`。表现是详情页
  `Object.keys(null)` 抛 TypeError，被错误边界接住渲染成整页「页面报错」，
  用户看不出问题出在一列附注上。修法：`selectByRunId` 改成 default 方法里的
  `selectList(wrapper)`，让 MyBatis-Plus 自己拼 SQL 从而用上 `autoResultMap`；
  原来写在 SQL 里的排序（`group_key` + `ALL→让球→大小球`）挪到内存，行数上限 8×3。
  `BacktestMetricsMappingTest` 用反射钉住两件事：`selectByRunId` 不许改回 `@Select`、
  两个 jsonb 字段不许删 `typeHandler`。**这两件事都不会让编译失败**，
  只会在真实库上让某一列悄悄变 null。
  同类约定：`OddsHistoryArchiveMapper` 的两个 `SELECT *` 方法返回的实体，
  `payload` 恒为 `null`，只读状态字段——已在该方法上写明。
- **详情页还有两处「类型与实现对不上」的显示错误**（不是崩溃，但同样是读代码读不出来的）：
  - `BacktestResultStatus` 前端声明成 `PENDING | SETTLED | VOID | NO_RESULT | FAILED`，
    后端只产生 `PENDING | FINAL | VOID`——五个值里四个不存在，真实值 `FINAL` 反而不在集合里。
    所以除 `=== 'PENDING'` 之外的判断全在猜。已按后端枚举改正，前端测试里的 fixture 也跟着改。
  - 逐场明细的 λ（期望进球数 1.96 / 1.11）用 `formatProbability` 渲染成了
    「196.1% / 111.0%」。λ 不是概率，改成两位小数直写。
- **此前那条「40% 样本被 λ 不可行吞掉」的结论已被推翻（2026-10-28）。**
  当时依据 `decision_stage = INFEASIBLE` 判断「反解无解」，但那个值本身是假的：
  `AsianDecisionEngine.skipped()` 构造跳过结果时把 `solution` 置空，而
  `BacktestDecisionStageEnum.of()` 只要看到 `solution == null` 就落到 `INFEASIBLE`。
  于是「反解成功但邻线超限」和「反解无解」被写成同一个值——44 场、117 场、160 场
  全成了「λ 解不出来」，而它们**一场都不是**。
  现在 `skippedWithDiagnostics()` 把 `solution` 与 `neighborCheck` 一并带出，
  `backtest_cases.infeasibility_reason`（V30）承载四种不可行原因的细分。重跑后真相：

  | 区间 | 决策 | 邻线超限 | λ 真无解 | 低置信 | 缺数据/分歧 |
  |---|---|---|---|---|---|
  | 7 月 | 127 | 45 | **0** | 22 | 1 |
  | 8 月 | 243 | 117 | **0** | 43 | 9 |
  | 9 月 | 199 | 160 | **5** | 38 | 4 |

  9 月那 5 场全是 `HANDICAP_PROBABILITY_OUT_OF_RANGE`（赢盘概率超出搜索域）。
  残差超限三天**全为 0**——主盘两方程恰定，残差只有 1e-16 量级，本来就不可能超限。

  **扼杀样本的是 `neighbor-tolerance = 0.04`，而且它切在了分布左侧**：
  被拒场次的邻线偏差 min 0.0400~0.0409、p50 0.047~0.050、p90 0.066~0.069，
  **八成的场次落在 0.04~0.06 这一档**。这些场次不是「两个市场打架」，而是独立
  Poisson 在邻线上的系统性形状偏差刚好越过一条卡得很紧的线。放宽到 0.06 可救回
  约八成被拒场次，0.10 救回 98%。但方向要记清楚：**放宽只增加样本量，不产生
  alpha**——`edge ≡ 0` 不变，多出来的样本只让统计功效更高，不会让期望收益转正。
  **实测反证：放宽会让 ROI 更差，这道闸门其实在过滤坏样本。** 八月同区间、同参数组，
  只改 `ASIAN_MODEL_NEIGHBOR_TOLERANCE` 重跑（run #23 vs #21）：

  | 容差 | 决策 | 腿 | 结算 | 加权命中 | ROI |
  |---|---|---|---|---|---|
  | 0.0400 | 243 | 365 | 357 | 50.45% | **−0.89%** |
  | 0.0600 | 324 | 484 | 474 | 49.55% | **−2.39%** |

  反推新增那 117 条腿的 ROI 是 **−6.96%**，远差于原样本的 −0.89%。
  所以邻线偏差不是「市场打架」的度量，而是**模型不可信的度量**：模型与市场在其他
  盘口线上分歧越大，越说明这场的解是 Poisson 形状偏差的产物而非信息优势。
  往更紧的方向测没有意义——8 月会掉到 200 腿以下，ROI 置信区间 ±7%，读不出差异，
  而按单月结果反向调容差本身就是过拟合。**容差保持 0.04。**
- **伤停因子在回测里不可用，这条口子已经堵死（2026-10-28 实测）。**
  `football-info` 确实对历史场次返回 `injuries[]`，字段也齐全
  （`playerName / teamType / position / isInjured / isSuspended`），看起来正好能回答
  「这场谁缺阵」。**但它不是当场快照。** 决定性证据在同一份响应内部：

  | 场次 | 日期 | 赛事 | `injuries[]` | 同一响应 `players[]` |
  |---|---|---|---|---|
  | 2040244 | 06-21 | 德国 vs 科特迪瓦 | 穆西亚拉 `isInjured=true` | 出场 1、**首发 1** |
  | 2040291 | 06-26 | 厄瓜多尔 vs 德国 | 穆西亚拉 `isInjured=true` | 出场 2、**首发 2** |
  | 2040344 | 06-30 | 德国 vs 巴拉圭 | 穆西亚拉 `isInjured=true` | 出场 3、**首发 3** |

  一个被标「伤缺」的球员，在同一份数据里场场首发。而 `players[]` 的赛季累计出场数
  是**真·逐场快照**：首尔 FC 的 14 名球员跨 4 场逐个 +1、14/14 单调不减
  （`金珍洙 14→15→16→17`、`孙正范 12→13→14→15`）。两者只有一个能被这样钉住，
  所以问题只能出在 `injuries[]`。旁证：被标伤停的球员占其球队归档场次的比例
  已经出现 3/3、4/4 的满覆盖，而真实伤停是有起止的。

  **结论：`injuries[]` 大概率是「查询时的当前状态」被复制进每一场。**
  拿它做回测就是用未来信息，做出来再漂亮的 ROI 也不是 alpha。随机抽 60 场测过
  覆盖率（各 1 次调用，零失败）：`injuries` 非空 77%（受伤 73%、停赛 7%）、
  `players` 两队全空 30%。归档到 150 场时判定作废，**没有继续烧剩下的约 1000 次额度**。

  可用的部分是 `feature` / `result`：滚动 20 场、按场次时点重新生成
  （首尔 FC 四场 `homeTeamTotalGoals` 34→31→31→32，`W/D/L` 同步变化，
  `homeTeamAvgGoals ≡ homeTeamTotalGoals / 20`），且 `resultDetails`（两队各近 20 场明细）
  的最大日期止于开赛前。**这是目前唯一独立于市场的 λ 来源**——本地 `matches` 只有
  4 个月、每队十来场，滚不满 20 场，`/tf/odds-history` 又只有约 4 个月。
  注意 `history` 块是**两队 H2H**（`totalMatches` 常为 2~3），不是近 20 场，别拿错。
- **新增落库列还有第四处要改：手写 INSERT 的列清单。**
  `BacktestCaseMapper.insertIgnore` 是固定列数的 `@Insert`，不走 MP 自动映射。
  V30 加列之后 Entity 有字段、`BacktestCaseBuilder` 也填了值，**但 SQL 里没有这一列**，
  于是它永远为 null、不报错不警告，白跑一轮八月回测才发现。
  现在 `BacktestCaseMapperSqlTest` 反射遍历 Entity 全部字段（排除自增、自动填充与
  结算阶段那七列），断言每个列名都出现在 `insertIgnore` 的 SQL 里。再加列忘改 SQL 时，
  测试会先挂，不用等一轮回测。
- **`BacktestMigrationIT` 未真跑。** 本机无 Docker，它只在 `-Pintegration` 下存在。
  生产库的 V29 是逐条执行验证的（见 `tools/backtest-schema-verify/`），
  但 CI 的空库全量重放没走过。
- **σ 不可与线上阈值互比**（§2）——历史报价家数更少，σ 系统性偏小。
- **决策时点必须显式固定**：同一场在 T−45h / T−24h / T−18h 让球主 MEDIUM，
  T−9h / T−6h LOW，T−3h 客 MEDIUM，收盘 LOW。结论对时点极度敏感，
  所以时点进了 `backtest_cases` 唯一键，而不是一个全局配置。
- **不做跨 run 汇总。** 两个 run 的样本区间与版本号都可能不同，汇总出的 ROI 没有意义。
- **回测明细不可删。** `backtest_cases` 有 DELETE 守卫，所以跑废的 run 会留在库里
  （状态为 `FAILED` 或指标为空）。清理需要绕过守卫，不值得——它们本身就是
  「这个功能在哪一版上出过什么错」的记录。公开端只显示 `SUCCEEDED` / `PARTIAL`，
  但一个「成功却没有方向」的 run 仍会出现在公开列表里，靠指标表的
  「这批样本没有任何方向」提示来消歧。
