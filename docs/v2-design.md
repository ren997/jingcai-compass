# 竞彩罗盘 v2 精简版开发方案

> 状态：**已确认，进入实施**（M0 已完成，下一步 M1）
> 版本：0.3 · 2026-10-20（市场由 `h2h+totals` 改为 `spreads+totals` 亚盘）
> 取代：v1 的「体彩竞彩双源 + 映射 + 人工复核」链路

## 1. 目标与非目标

### 1.1 目标

用**单一数据源**跑通一条可审计的预测闭环，并把 v1 里因数据源不配合而产生的复杂度全部移除。

闭环固定为四步：

```
每日抓赔率 → 生成预测并发布 → 开赛即锁定 → 赛后自动结算 → 全量历史公开
```

### 1.2 非目标（明确不做）

| 不做 | 原因 |
|---|---|
| 竞彩/体彩赛事池 | 端点被 WAF 拦截，且与 The Odds 无共享主键，是 v1 复杂度的根源 |
| 赛事/球队/联赛映射层 | 单源下 `event id` 天然一致，此层无存在理由 |
| 人工结果补录后台 | `scores` 端点直接给赛果 |
| 结算版本链与 SUPERSEDE 审计 | 单次结算 + 允许重算覆盖，只保留 `calc_version` 标记 |
| 用户系统、付费、社区 | 不在本阶段 |
| 实时滚球 | 需要多 region 高频抓取，配额与 v1 目标都不允许 |

### 1.3 保留（v1 的真正价值）

简化不等于退化成"又一个推单站"。以下三条必须保留，成本极低：

1. **发布即锁定**：开赛时间到达后，预测核心字段不可修改（数据库层约束 + 服务层校验双保险）。
2. **内容哈希**：每条预测在发布时计算 `content_hash`，对外暴露，用于事后校验未被改写。
3. **历史不删改**：错的一样保留，统计口径公开可复算。

## 2. 范围

| 维度 | 内容 |
|---|---|
| 联赛 | 英超 `soccer_epl`、西甲 `soccer_spain_la_liga`、意甲 `soccer_italy_serie_a`、德甲 `soccer_germany_bundesliga`、欧冠 `soccer_uefa_champs_league` |
| 市场 | **让球盘 `spreads`（亚盘）+ 大小球 `totals`（亚盘）**，不做胜平负 `h2h`；两个市场在同一请求返回，但**按市场分别计费**，见 4.2 |
| 赔率格式 | 十进制 `oddsFormat=decimal` |
| 时区 | 全部按 `Asia/Shanghai` 展示，存储统一 `timestamptz` |
| 联赛命名 | 静态中文词典，队名第一版显示英文原名 |

补位池（荷甲、葡超、土超、巴甲、日职、美职）**不进第一版**，仅在配置里预留 `tier=SUPPLEMENT`，默认 `enabled=false`。

## 3. 数据源事实（已实测，作为设计前提）

| 事实 | 影响 |
|---|---|
| `odds` 与 `scores` 共用同一个 `event id` | **映射层可直接删除**，这是本方案成立的核心前提 |
| `scores` 带 `daysFrom` 最多回溯 **3 天** | 同步失败超过 3 天，赛果永久丢失 → 必须有超期告警 |
| `scores` 带 `daysFrom` 计费 2 credits，不带 1 credit | 隔日跑 `daysFrom=2` 可摊薄到 5 credits/天 |
| `odds` 计费 = 市场数 × 地区数 | `spreads,totals` × 1 region = **2 credits/联赛**（换市场不影响单价） |
| `/v4/sports`、`/v4/sports/{key}/events` **免费** | 探活零成本，可先判断"有没有比赛"再决定是否花钱 |
| `/odds` 不接受日期参数，返回该联赛**全部未开赛**赛事 | 一次调用即可覆盖未来整个窗口，无需按天抓 |
| 免费档 500 credits/月 | 决定下面的频率设计 |
| `scores` 仅覆盖部分运动 | 上线前必须逐联赛确认，见 4.1 |

### 3.1 让球盘实测（2026-10-20，`regions=eu`，英超 20 场）

**结论：`spreads` 确实是亚盘，能拿到 `-0.25 / -0.75 / -1.25` 这类四分之一盘，但数据比 `totals` 薄得多，且各家报的盘口不一致。** 这直接改变了聚合算法（见 7.1）。

盘口线分布（20 场全部 bookmaker × outcome）：

| 主队 point | -1.5 | -1.25 | -1.0 | -0.75 | -0.5 | -0.25 | 0.0 | +0.25 | +0.5 | +0.75 | +1.0 | +1.25 | +1.5 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 出现次数 | 12 | 2 | 18 | 10 | 36 | 14 | 76 | 14 | 36 | 10 | 18 | 2 | 12 |

关键事实：

| 观测 | 含义 |
|---|---|
| 18 家 bookmaker 中**只有 8 家**提供 `spreads`，`totals` 有 18 家 | 让球盘样本薄，聚合要更宽容 |
| 同一场比赛，各家报的盘口**最多相差一整球**（利物浦场：-1.0 / -0.5 / -0.25 / 0.0） | **不能跨盘口取中位数**，必须先定线再聚合 |
| 真正的亚盘线只有 Pinnacle、Matchbook 报（阿森纳 -1.25 @1.93/1.97，双边接近 1.95） | Pinnacle 是亚盘基准，可作为线上校验 |
| 个别书报出明显离群价（利物浦 coolbet 主 -1.0 @3.20，对应客 +1.0 @1.39） | 必须有离群值剔除 |
| `totals` 的 2.5 出现 442 次、3.0 仅 42 次 | 大小球有明显主盘，聚合稳定 |

**这条实测推翻了一个原本的假设**：`h2h` 里所有书报的是同一组三个结果，取中位数天然成立；`spreads` 和 `totals` 里每家的 `point` 不同，中位数没有意义。所以算法必须多一步"选线"。

### 3.2 关键设计推论

因为 `/odds` 一次返回该联赛所有未开赛赛事，**不需要为了覆盖多天而增加调用次数**。真正的约束是"预测要在开赛前足够早发布"，而不是"要抓几次"。

因此发布窗口定义为：

```
now + 3h  ≤  commence_time  ≤  now + 30h      → 生成并发布预测
commence_time >  now + 30h                    → 只入库，不发布
commence_time <  now + 3h                     → 跳过（来不及了，宁可不发）
```

这个窗口同时解决了两个问题：既贴近开赛（预测更像收盘共识），又留出至少 3 小时的"公开可查"时间。

## 4. 配额预算

### 4.1 M0 数据源验证结果（2026-10-20 22:10 实测，已完成）

调用 `GET /v4/sports/{key}/scores?daysFrom=3`，5 个联赛各一次，共消耗 **12 credits**（余额 498 → 486）。

| 联赛 | 返回赛事 | 已完赛 | 含 `scores` | 结论 |
|---|---|---|---|---|
| 英超 | 26 | 6 | 6 | ✅ 覆盖正常 |
| 西甲 | 29 | 8 | 8 | ✅ 覆盖正常 |
| 意甲 | 28 | 8 | 8 | ✅ 覆盖正常 |
| 德甲 | 21 | 3 | 3 | ✅ 覆盖正常 |
| 欧冠 | 18 | **0** | 0 | ⚠️ **待复核** |

**结论一：四个联赛的赛果覆盖全部确认。** `scores` 对象字段为
`id / sport_key / sport_title / commence_time / completed / home_team / away_team / scores[] / last_update`（9 个字段），`scores[]` 内是 `{name, score}`，`score` 为字符串。

**结论二：欧冠暂时无法确认。** 拉取时点（10-20 晚）距上一轮欧冠已超过 3 天，而下一轮在 10-21/22。这是 `daysFrom` 上限 3 天导致的**观测窗口错位**，不是数据缺失。处理方式：10-23 之后重跑一次该联赛校验，通过前欧冠在配置里标记为 `enabled=false`，不影响其余四个联赛开工。

**结论三：`scores` 端点同时返回未开赛场次**，`completed=false` 且无 `scores` 字段。这意味着单次调用即可拿到「已完赛 + 未开赛」全量，无需再调 `/events`。这会让同步逻辑简化一处，但也意味着**不能靠"有没有 `scores` 字段"判断赛事不存在**，必须看 `completed`。

**结论四：英超赛事池干净。** 抽查 26 场，球队恰好构成 20 支，无杯赛混入（Coventry / Hull / Ipswich 为本赛季升班马，非数据错误）。联赛标签可放心使用。

### 4.2 每日成本

**先澄清一个常见误解**：让球和大小球确实在**同一个请求**里返回，参数是 `markets=spreads,totals`，不需要调两次。但计费规则是「**市场数 × 地区数**」，不是按请求数。所以：

```
markets=spreads             → 1 credit / 联赛
markets=spreads,totals      → 2 credits / 联赛     ← 请求数相同，价格翻倍
```

**所以：换成亚盘不改变成本。** 2 credits/联赛 这个单价，与你做 `h2h+totals` 完全一样——计费只看"几个市场"，不看市场类型。

| 任务 | 频率 | 计算 | 月成本 |
|---|---|---|---|
| 联赛探活 `/events` | 每天 1 次 × 5 | 免费 | 0 |
| 赔率 `/odds`（`spreads,totals`） | 每天 1 次 × 5 | 5 × 2 credits | 300 |
| 赛果 `/scores?daysFrom=2` | 每 2 天 1 次 × 5 | 5 × 2 ÷ 2 | 150 |
| **合计** | | | **450 / 500** |

余量仅 50 credits/月，约 25 次手动调试。这是本项目最需要克制的地方。

**自动降级规则**（写进 `ReconcileJob`，不靠人记）：

```
if quota_remaining < 100:
    本次及后续 odds 请求自动改用 markets=totals   （月成本降到 150 + 150 = 300）
    写 WARN 日志 + sync_runs 标记 degraded=true
    统计页标注"当前为降级模式：仅大小球"
```

降级保留大小球而不是让球，原因是实测数据显示：`totals` 有 18 家报价、主盘集中（2.5 出现 442 次），可靠度远高于只有 8 家报价且盘口分散的 `spreads`。额度紧张时先砍不可靠的那个。

### 4.3 超预算时的降级顺序

1. 只保留大小球（`markets=totals`）→ 300/月（已由上面的自动规则覆盖）
2. 联赛从 5 降到 3 → 约 180/月
3. 上 $30 付费档（20,000 credits），恢复双市场 + 每日赛果 + 多 region 取中位数

## 5. 系统架构

```
backend-v2/   Spring Boot 3.3.9 · Java 21 · MyBatis-Plus · PostgreSQL · Flyway
web-v2/       React 18 · Vite · antd · React Query
```

### 5.1 与 v1 的关系

- **完全独立**：独立目录、独立数据库（`jingcai_v2`）、独立 Flyway 基线（从 `V1` 开始）。
- 不修改 `backend/`、`frontend/`、`docs/dev-tasks.md` 任何内容。
- 唯一共享的是仓库级规范 `AGENTS.md`（命名、包结构、注释、提交信息）。
- 旧代码保留，作为结算算法（尤其亚盘/大小球拆盘）的实现参考。

### 5.2 模块划分

按业务模块优先，模块内再分技术角色，与 `AGENTS.md` 一致：

| 模块 | 职责 |
|---|---|
| `league` | 联赛配置与展示名 |
| `event` | 赛事入库、状态流转、锁定 |
| `odds` | The Odds API 客户端、赔率解析、去水、快照写入 |
| `prediction` | 预测生成、发布、锁定、内容哈希 |
| `result` | 赛果同步与入库 |
| `settlement` | 结算算法、净收益计算 |
| `sync` | 同步运行记录、配额统计、定时任务编排 |
| `stats` | 命中率、ROI、Brier、校准分桶 |
| `common` | 统一响应封装、异常处理、管理端鉴权 |

### 5.3 定时任务

单实例部署，使用 `@Scheduled` + 固定时区 `Asia/Shanghai`。所有"是否该锁定/是否超期"的判断**用数据库时间 `now()`**，不信任 JVM 时钟（沿用 v1 结论）。

| 时刻 | 任务 | 说明 |
|---|---|---|
| 08:00 | `OddsSyncJob` | 探活 → 只对有赛事的联赛调 `/odds` → 写快照 → 生成并发布窗口内预测 |
| 08:10 | `LockJob` | `commence_time <= now()` 的预测置为 `LOCKED` |
| 08:20 | `ScoresSyncJob` | 奇数日执行，`daysFrom=2` 拉赛果 → 写 `match_results` |
| 08:30 | `SettlementJob` | 对已出结果且未结算的预测执行结算 |
| 09:00 | `ReconcileJob` | 对账：超期未锁定、超期未结算、赛事已过期仍 `SCHEDULED` → 记告警日志 + `sync_runs` 标记 |

选择早上 8 点的原因：欧洲联赛多在北京时间 20:00–04:00 开赛，8 点运行时"未来 30 小时"窗口正好覆盖当晚和次日凌晨。

## 6. 数据模型

7 张表，库 `jingcai_v2`。表与关键列按 `AGENTS.md` 要求加中英双语 `COMMENT ON`。

### 6.1 `leagues` 联赛

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | |
| `sport_key` | varchar(64) UNIQUE | `soccer_epl` |
| `name_en` | varchar(128) | API 原始 `title` |
| `name_zh` | varchar(64) | 静态词典 |
| `tier` | varchar(16) | `PRIMARY` / `SUPPLEMENT` |
| `enabled` | boolean | 默认 false，由 seed 显式开启 5 个 |
| `sort_order` | int | 展示顺序 |

### 6.2 `events` 赛事

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | |
| `external_id` | varchar(64) UNIQUE | The Odds `event id`，**全表唯一主键** |
| `league_id` | bigint FK | |
| `commence_time` | timestamptz | 开赛时间，锁定依据 |
| `home_team` / `away_team` | varchar(128) | 英文原名 |
| `status` | varchar(16) | `SCHEDULED` / `LOCKED` / `FINISHED` / `CANCELED` |
| `first_seen_at` / `last_seen_at` | timestamptz | 何时首次/最后出现在 API |

索引：`(commence_time)`、`(league_id, commence_time)`、`(status)`。

### 6.3 `odds_snapshots` 赔率快照（只追加）

每行 = 某批次中某赛事的某市场某结果的赔率。用 `batch_id` 把一次抓取的多行归组，避免额外建批次表。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | |
| `batch_id` | uuid | 同一轮同步共享 |
| `event_id` | bigint FK | |
| `market_type` | varchar(16) | `SPREADS` / `TOTALS` |
| `line` | numeric(4,2) NOT NULL | 该市场选定的主盘线，**两个市场都有 line** |
| `outcome` | varchar(16) | `HOME`/`AWAY`（让球）、`OVER`/`UNDER`（大小球） |
| `price` | numeric(8,3) | 同一 `line` 上去水后的中位赔率 |
| `implied_prob` | numeric(6,5) | 同线逐家去水 → 取中位 → 再归一 |
| `bookmaker_count` | int | **报价该 `line`** 的博彩公司数 |
| `captured_at` | timestamptz | |

索引：`(event_id, captured_at DESC)`、`(batch_id)`。

`price` 与 `implied_prob` 同时存，是为了让历史页能"按当时的赔率"复算 ROI，而不是按现在的赔率。

### 6.4 `predictions` 预测

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | |
| `event_id` | bigint FK | |
| `market_type` | varchar(16) | `SPREADS` / `TOTALS` |
| `line` | numeric(4,2) NOT NULL | 选定主盘线，锁定后不可改 |
| `market_prob` | numeric(6,5) | `pick` 在市场去水后的概率（基线） |
| `model_prob` | numeric(6,5) | 模型概率，v1 等于 `market_prob` |
| `pick` | varchar(16) | `HOME`/`AWAY`（让球）、`OVER`/`UNDER`（大小球），**无 `DRAW`** |
| `pick_price` | numeric(8,3) | 发布时该结果的赔率 |
| `pick_prob` | numeric(6,5) | |
| `edge` | numeric(6,5) | `pick_prob - 0.5`，即相对五五开的偏离度 |
| `content_hash` | char(64) | 对核心字段规范化后的 SHA-256 |
| `model_version` | varchar(32) | `odds-baseline-v2` |
| `status` | varchar(16) | `PUBLISHED` / `LOCKED` / `VOID` |
| `published_at` / `locked_at` | timestamptz | |

唯一约束：`(event_id, market_type, line, model_version)`，保证重复同步不产生重复预测。

### 6.5 `match_results` 赛果

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | |
| `event_id` | bigint UNIQUE FK | |
| `home_score` / `away_score` | int | |
| `total_goals` | int | 生成列，结算直接用 |
| `finished_at` | timestamptz | |
| `revision` | int | 比分更正时 +1，不覆盖历史语义 |
| `raw_payload` | jsonb | 原始 `scores` 对象，保留原始事实 |

### 6.6 `settlements` 结算

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | |
| `prediction_id` | bigint UNIQUE FK | 一条预测一次结算 |
| `result` | varchar(16) | `WIN`/`HALF_WIN`/`PUSH`/`HALF_LOSS`/`LOSS`/`VOID` |
| `stake` | numeric(10,2) | 默认 1 |
| `profit` | numeric(10,4) | 净收益，见 7.3 |
| `settled_at` | timestamptz | |
| `calc_version` | varchar(32) | 结算算法版本，便于重算追溯 |

### 6.7 `sync_runs` 同步记录

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | |
| `job_type` | varchar(32) | `ODDS_SYNC`/`SCORES_SYNC`/`LOCK`/`SETTLE`/`RECONCILE` |
| `trigger_type` | varchar(16) | `CRON`/`MANUAL` |
| `status` | varchar(16) | `RUNNING`/`SUCCESS`/`PARTIAL`/`FAILED` |
| `leagues_targeted` / `leagues_ok` | int | 探活与实际成功数 |
| `events_upserted` / `snapshots_written` | int | |
| `credits_used` | int | 从响应头 `x-requests-last` 累计 |
| `quota_remaining` | int | 从 `x-requests-remaining` 读取 |
| `error_message` | text | |
| `started_at` / `finished_at` | timestamptz | |

**`credits_used` 是每天要看第一眼的指标**，它直接对应本月会不会断供。

## 7. 核心算法

### 7.1 选线：先定盘，再聚赔

`spreads` 和 `totals` 每家的 `point` 不同（实测同一场最多相差一整球），所以**不能跨盘口取中位数**。必须先选出一条主盘线，再只聚合这条线上的报价。

**主盘定义：让两边概率最接近五五开的那条线。** 这正是亚盘"标准盘"的本义。

```
1) 逐家按各自 line 去水：p_i = (1/price_i) / Σ(1/price)
2) 按 line 分组，对每组取 p 的中位数 → p̄(line)
3) 选 argmin |p̄(line) - 0.5| 作为主盘 line
4) 主盘上报价家数 < 2 → 本场不生成预测
5) 该 line 上若某家 p 与中位数偏离 > 0.08，剔除后重算一次
```

这条规则在实测数据上验证过，结果与 Pinnacle 一致：

| 比赛 | 各家报的 line | 各线 p̄ | 选中 | Pinnacle 报的 |
|---|---|---|---|---|
| 阿森纳 vs 埃弗顿 | -1.5(7家) / -1.25(2家) | -1.5 → 0.445，**-1.25 → 0.505** | **-1.25** | -1.25 |
| 维拉 vs 曼城 | 0.5(2) / 0.75(2) / 1.0(4) / 1.5(1) | 0.5→0.445，**0.75→0.494**，1.0→0.556 | **0.75** | 0.75 |
| 利物浦 vs 布莱顿 | -1.0(1) / -0.5(3) / **-0.25(3)** / 0.0(2) | -0.25 最接近 0.5 | **-0.25** | -0.25 |

三次都命中 Pinnacle 的盘，说明"最平衡线"这个判据可以替代"众数"——众数在这里会选错（阿森纳场众数是 -1.5，比真亚盘深了 0.25）。

### 7.2 同线聚合与去水

在两个选项的市场里（让球、大小球都是二选一），只需聚合一边：

```
1) 在选定 line 上，取 p_home（或 p_over）的中位数 p̄
2) 另一边直接补 1 - p̄        ← 二选一市场无需再归一
3) bookmaker_count 记录实际参与的家数，写入快照
```

**顺序很重要**：先逐家去水再取中位，比"先取中位赔率再去水"更稳，可以抵抗个别异常赔率（错价、延迟更新）的干扰。

### 7.3 结算规则

两个市场都是"让分后比大小"，可以统一成一个函数：先算 `margin`，再按盘口类型拆盘。

**符号约定**：API 返回的 `point` 是**加在主队比分上的让球数**。主队被让球时为正（弱队受让），主队让球时为负。
实测可验证：维拉 vs 曼城，主队 `+0.75`（受让）；阿森纳 vs 埃弗顿，主队 `-1.25`（让球）。

```
让球盘：margin = (home_score - away_score) + line      ← pick=HOME 用
        margin = (away_score - home_score) - line      ← pick=AWAY 用
大小球：margin = (home_score + away_score) - line      ← pick=OVER 用
        margin = line - (home_score + away_score)      ← pick=UNDER 用
```

**判定**：`margin > 0` 赢，`margin = 0` 走，`margin < 0` 输。

**四分之一盘拆盘**（`line` 小数部分为 `.25` 或 `.75`）：拆成 `line-0.25` 和 `line+0.25` 两个半盘，各算一次 `margin`，再按合并表定结果。

| 两个半盘结果 | 最终结果 |
|---|---|
| 赢 + 赢 | `WIN` |
| 赢 + 走 | `HALF_WIN` |
| 走 + 走 | `PUSH` |
| 输 + 走 | `HALF_LOSS` |
| 输 + 输 | `LOSS` |

整数盘（`.0`）和半球盘（`.5`）不拆，直接判定；整数盘可能出现 `PUSH`。

**验算示例**：阿森纳 -1.25 胜埃弗顿 1:0，`margin = 1 - 1.25 = -0.25`
→ 拆 `-0.5`（输）和 `0`（走）→ `HALF_LOSS`，即"让一球/球半，赢一球输半"。✅

净收益（`stake = 1`）：

```
WIN       = price - 1
HALF_WIN  = (price - 1) / 2
PUSH      = 0
HALF_LOSS = -0.5
LOSS      = -1
```

### 7.4 统计指标

| 指标 | 定义 | 注意 |
|---|---|---|
| 全赢率 | `WIN / (WIN+HALF_WIN+HALF_LOSS+LOSS)` | `PUSH` 不进分母，单独展示走盘比例 |
| 含半赢率 | `(WIN + 0.5×HALF_WIN) / 同上分母` | 两个口径都要上页面，避免"半赢当全赢"的美化 |
| ROI | `Σ profit / Σ stake` | 按 `pick_price`（发布时赔率）计算，不用当前赔率；`PUSH` 退还本金，profit=0 |
| Brier | 二分类：`(pick_prob - o)²`，`o∈{0,1}` | 两个市场都是二选一，统一一个公式；`PUSH` 场次不计入，单独报告占比 |
| 校准 | 按 `pick_prob` 分 10 桶，比较桶内实际命中率 | 校准曲线的斜率应接近 1 |

**必须在统计页并列展示 `market_prob` 的 Brier。** v1 的 `model_prob == market_prob`，两个数必然相同——这不是 bug，而是要在页面上明确写"当前模型无独立信息，Brier 与市场基线一致"。有这个对照，将来换模型才知道是不是真的变好了。

### 7.5 内容哈希

对 `event_id | market_type | line | pick | pick_prob | model_version | published_at` 做规范化字符串拼接后 SHA-256。字段顺序与格式写死在代码中并有单测覆盖，任何改动都视为破坏兼容。

## 8. API 契约

统一响应封装沿用 `AGENTS.md` 的 `system/api` 风格：`{ code, message, data }`。

### 8.1 公开接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v2/leagues` | 启用的联赛列表（含中文名） |
| GET | `/api/v2/events?date=&league=&status=` | 赛事列表，含预测摘要 |
| GET | `/api/v2/events/{externalId}` | 详情：赔率快照、预测、赛果、结算 |
| GET | `/api/v2/settlements?page=&size=&league=&market=` | 历史结算，按开赛时间倒序 |
| GET | `/api/v2/stats/overview?from=&to=` | 命中率 / ROI / Brier / 样本量 |
| GET | `/api/v2/stats/calibration?market=` | 校准分桶 |
| GET | `/api/v2/stats/by-league` | 按联赛、按市场拆分 |

不需要登录。预测详情页必须返回 `content_hash` 和 `published_at`，让用户能自行核对。

### 8.2 管理接口

`X-Admin-Key` 头校验，key 从环境变量读，不进配置文件。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v2/admin/sync/odds` | 手动触发赔率同步（`?dryRun=true` 只探活不花钱） |
| POST | `/api/v2/admin/sync/scores` | 手动触发赛果同步，`daysFrom` 可传 |
| POST | `/api/v2/admin/jobs/lock` | 手动锁定 |
| POST | `/api/v2/admin/jobs/settle` | 手动结算 |
| GET | `/api/v2/admin/sync-runs?limit=` | 最近运行记录与 credits 消耗 |
| GET | `/api/v2/admin/quota` | 剩余额度（读 `x-requests-remaining` 缓存值） |

`dryRun` 是开发期的救命参数：它只调免费的 `/events`，能把"联赛有没有比赛、解析对不对"验证完，一分钱不花。

### 8.3 可观测

`/actuator/health`、`/actuator/prometheus`。核心指标：

- `odds_sync_credits_used_total`（counter）
- `odds_quota_remaining`（gauge）
- `predictions_published_total{market}`
- `settlements_total{result}`
- `unsettled_overdue_count`（gauge，按 `commence_time` 超过 24h 未结算计数）
- `odds_brier{market}` / `market_brier{market}`（gauge，两个一起看）

## 9. 目录与类清单

```
backend-v2/src/main/java/com/jingcaicompass/v2/
  V2Application.java
  common/
    api/ApiResponse.java, PageResponse.java
    exception/GlobalExceptionHandler.java, BizException.java
    config/AdminKeyFilter.java, SchedulingConfig.java, JacksonConfig.java
  league/{controller,dto,vo,entity,mapper,service}/
  event/{controller,vo,entity,enums,mapper,service}/
  odds/
    client/TheOddsApiClient.java, OddsApiProperties.java, dto/(OddsEventDto, BookmakerDto, MarketDto, OutcomeDto)
    service/OddsSyncServiceImpl.java, OddsNormalizer.java, OddsSnapshotWriter.java
  prediction/
    enums/MarketTypeEnum.java, PredictionStatusEnum.java, PickEnum.java
    service/PredictionGenerationServiceImpl.java, PredictionLockServiceImpl.java, ContentHashCalculator.java
  result/
    client/ScoreApiClient.java, dto/ScoreEventDto.java
    service/ScoreSyncServiceImpl.java, ResultWriter.java
  settlement/
    enums/SettleResultEnum.java
    service/SettlementServiceImpl.java, HandicapCalculator.java, SettlementWriter.java
  sync/
    job/OddsSyncJob.java, LockJob.java, ScoresSyncJob.java, SettlementJob.java, ReconcileJob.java
    entity/SyncRunEntity.java, mapper/, service/SyncRunService.java
  stats/
    controller/StatsController.java, service/StatsServiceImpl.java, vo/
```

```
backend-v2/src/main/resources/
  application.yml                     # 数据源、Flyway、actuator、业务开关
  application-local.yml               # 本地配置，进 .gitignore
  db/migration/V1__baseline_schema.sql
  db/migration/V2__seed_leagues.sql
  logback-spring.xml
```

```
web-v2/src/
  pages/TodayPage.tsx        # 今日/近期预测，卡片式
  pages/HistoryPage.tsx      # 历史结算表，可筛选联赛/市场/结果
  pages/StatsPage.tsx        # 命中率、ROI、Brier、校准曲线
  components/                # MarketTag、ProbBar、ResultBadge、HashBadge
  services/api.ts, types/api.ts
  i18n/leagues.ts            # 联赛中文词典
```

前端三页，不做后台管理界面。管理操作全部走 `curl`，避免为 6 个按钮写一套 CRUD。

## 10. 测试策略

### 10.1 单元测试（本地可跑，必须全覆盖）

算法部分是本项目唯一"错了就全盘皆输"的地方，优先覆盖：

| 目标 | 用例 |
|---|---|
| 去水 | 三项和不为 1 的赔率、两家分歧极大、单家异常值不污染中位数 |
| 选线与盘口 | 最平衡线命中 Pinnacle、离群价剔除、同线仅 2 家、`|p-0.5|` 完全并列时的稳定取舍 |
| 结算 | `.0/.5/.25/.75` 四种盘口 × 进球数 0~6 的组合表，逐格断言 |
| 哈希 | 字段顺序变化、空 line、浮点格式化稳定性 |
| 窗口 | `now+2h`/`now+3h`/`now+30h`/`now+31h` 四个边界 |
| 序列化 | 用真实 API 响应快照做 fixture，防止字段改名导致静默失败 |

结算矩阵建议直接写成参数化测试，把"盘口 × 比分 → 期望结果 → 期望收益"做成一张表，比逐条写更有保障。

### 10.2 不做集成测试（已决定）

**第一版不写集成测试，不引 Testcontainers，也不引 embedded-postgres。** 理由：v2 的表只有 7 张、迁移只有 2 个文件，集成测试的投入产出比在这个阶段不成立；先把闭环跑起来更重要。

替代方案是**可重复的手动冒烟**，按顺序执行，每步都有明确的可观察结果：

| # | 操作 | 期望 |
|---|---|---|
| 1 | `docker compose up -d postgres` 后启动应用 | Flyway 建出 7 张表，`/actuator/health` 返回 200 |
| 2 | `curl -X POST .../admin/sync/odds?dryRun=true` | 列出各联赛赛事数，`credits_used=0` |
| 3 | `curl -X POST .../admin/sync/odds` | `odds_snapshots` 有行，`sync_runs.credits_used` 与响应头一致 |
| 4 | 查 `predictions` | 窗口内的赛事各有一条 `PUBLISHED`，`content_hash` 非空 |
| 5 | 手动把某条预测的 `commence_time` 改到过去，跑 `jobs/lock` | 状态变 `LOCKED`；再试图改 `pick` 报错 |
| 6 | `curl -X POST .../admin/sync/scores?daysFrom=3` | `match_results` 写入，`revision=1` |
| 7 | 跑 `jobs/settle` | `settlements` 有行，`profit` 与手算一致 |
| 8 | 再跑一次 `jobs/settle` | 不产生重复行，`profit` 不变（幂等） |
| 9 | 打开统计页 | 样本数 = 结算行数，模型 Brier 与市场 Brier 并列显示 |

第 5、8 两步是**最容易出问题的地方**（锁定可绕过、结算不幂等），手工验证时必须做。

### 10.3 数据 fixture

从真实 API 抓一次响应存成 `src/test/resources/fixtures/`，包括：多 line 的 `spreads`、多 line 的 `totals`、离群价（酷玩 -1.0 @3.20 那场）、已完赛 `scores`、未开赛 `completed=false` 的 `scores`。这些 fixture 同时用于解析测试和选线/结算的端到端校验。

## 11. 里程碑与验收

| 里程碑 | 交付 | 验收标准 |
|---|---|---|
| **M0 数据源确认** ✅ | 赛果覆盖实测 | **已完成见 4.1**：英超/西甲/意甲/德甲覆盖确认；欧冠待 10-23 后复核 |
| **M1 骨架与数据层** | 工程、V1/V2 迁移、7 张表、`leagues` seed | 应用可启动，Flyway 成功，表结构带双语注释，`/actuator/health` 200 |
| **M2 赔率同步** | 客户端、解析、去水、快照写入、`sync_runs` | `dryRun` 能列出各联赛赛事；真实同步后快照行数符合预期，`credits_used` 与响应头一致 |
| **M3 预测与锁定** | 生成、发布、哈希、锁定任务 | 窗口边界正确；锁定后直接改库字段被拒绝（单测 + 冒烟第 5 步双重验证）；同一赛事重复同步不产生重复预测 |
| **M4 赛果与结算** | 赛果同步、结算、收益计算 | 结算矩阵单测全部通过；冒烟第 8 步证明幂等；取消赛事正确置 `VOID` |
| **M5 API 与前端** | 7 个公开接口、6 个管理接口、三页前端 | 前端三页可打开并连真实数据；统计页同时展示模型与市场 Brier；哈希在详情页可见 |
| **M6 观测与部署** | 指标、日志、docker-compose、定时任务 | 两个 Brier 指标可抓取；`unsettled_overdue_count` 有值即告警；compose 一键起服务 + PG |

**建议顺序**：M0 已完成，接下来 M1→M2→M3→M4 顺序推进，M5 可以和数据层并行，M6 最后。

**欧冠的补验**：10-23 欧冠赛果产生后，重跑一次 `scores?daysFrom=3` 确认有 `completed=true` 且带 `scores`。通过即启用，不通过则第一版只用四个联赛（配额还更宽松）。**这一步必须在 M4 之前完成**，否则欧冠的预测会永远结算不了。

## 12. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 赛果超 3 天未同步 | 永久丢失，历史出现空洞 | `ReconcileJob` 每日检查，超过 48h 未结算即告警；管理接口支持指定 `daysFrom=3` 手动补 |
| 配额耗尽 | 当月中途断供 | `ReconcileJob` 检测 `quota_remaining < 100` 时**自动降级为 `markets=totals`**（见 4.2），并告警；`dryRun` 优先 |
| 让球盘报价家数太少 | 主盘概率不可靠 | 主盘上 `< 2` 家则不预测；`bookmaker_count=2` 的场次在页面标注"数据稀薄" |
| 赛事延期 | `commence_time` 变化导致锁定失效 | 每次同步更新 `commence_time`；若赛事已 `LOCKED` 而时间被推迟，允许解锁并重新发布，同时记录原因 |
| 赛事取消 | 无赛果 | `CANCELED` → 预测置 `VOID`，结算 `VOID`，不计入命中率分母 |
| 比分更正 | 结算错误 | `match_results.revision + 1` 并重算结算，`calc_version` 递增 |
| 某个 line 只有 1-2 家报价 | 概率不可靠 | `bookmaker_count < 3` 直接跳过，宁可不预测 |
| 队名与用户认知不符 | 展示陌生 | 第一版显示英文原名；后续按用户反馈补静态别名表，不做机器翻译 |
| 产品合规 | 展示赔率 + 预测接近荐彩 | 不涉代下单、不涉充值返佣、不用"必中/稳赚"表述；统计页公开全部历史含亏损 |

## 13. 决策记录

2026-10-20 已确认，可据此开工：

| # | 结论 | 说明 |
|---|---|---|
| 1 | **联赛**：英超、西甲、意甲、德甲、欧冠 | 四个已确认赛果覆盖，欧冠待 10-23 复核 |
| 2 | **市场**：`spreads`（亚盘让球）+ `totals`（大小球），**不做胜平负** | 同一请求返回，计费仍按市场数翻倍（2 credits/联赛），单价与 `h2h+totals` 相同；额度低于 100 时自动降级为仅 `totals` |
| 2.1 | **主盘定义**：选让两边概率最接近 50/50 的那条线 | 实测三次均与 Pinnacle 报的盘一致；"众数"判据会选错（阿森纳场众数 -1.5，真盘 -1.25） |
| 3 | **测试**：只做单元测试 + 手动冒烟，不做集成测试 | 不引 Testcontainers，不引 embedded-postgres；冒烟清单见 10.2，重点是第 5 步（锁定）和第 8 步（幂等） |

下一步：M1 骨架与数据层。同时新建 `docs/v2-tasks.md` 作为 v2 独立看板，不改 `docs/dev-tasks.md`。
