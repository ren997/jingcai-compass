# NovaAPI 数据源实测报告

> 实测日期：2026-10-21，日卡凭证，13 场竞彩（日职 3 / 欧冠 6 / 西甲 1 / 解放者杯 1 等）
> 探测工具：`tools/novaapi-probe`（`java NovaApiProbe.java all`，13 个请求）

## 结论

**GO。** 数据可满足亚盘/大小球建模与 90 分钟结算，历史赔率也可回溯，回测不需要从零攒数据。

| 判定项 | 结果 | 证据 |
|---|---|---|
| 竞彩赛程 | ✅ | 当日 13 场，`matchId` 贯穿赛程/赔率/赛果 |
| 机构赛前赔率 | ✅ | 一次调用返回全部机构×玩法（日职 5 家 / 欧冠 7 家） |
| 亚盘让球 | ✅ | 平博 18 个选项、Bet365 30 个选项，多档盘口 |
| 四分之一盘 | ✅ | 实测出现 `-0.25 / +0.75 / -1.25 / 2.75 / 3.25` 等 |
| 大小球 | ✅ | 平博 20 个选项，盘口 1.75~4.0 |
| 赛果（90 分钟） | ✅ | `sectionsNo999` + `ftHomeScore`，与加时/点球字段分离 |
| 赛果回溯 | ✅ | 至少回溯到 2026-01（9 个月前），单次查询上限 30 天 |
| 历史赔率 | ✅ | 29 天前赛事仍有 2907 个 `points` 数据点 |
| 事件流 | ✅ | `fixture-detail` 返回 10 条事件（含 `elapsedMin`） |

## 鉴权（已跑通，含两个真实坑）

Ed25519 + 三请求头：`X-API-Key` / `X-Timestamp`（毫秒）/ `X-Signature`（base64）。
待签串 = `apiKey={key}&timestamp={ts}` + 其余 **参与验签的** 业务参数按 key 升序 `&k=v`。

**坑 1：签名参数必须与接口真实接受的参数完全一致。** 给 `/nova/sports-lottery/list` 传 `date=` 会直接报 401：

```
当前请求除 apiKey/timestamp 外无业务参数参与验签
```

因为该接口**不接受任何参数**（固定返回当天）。要按日期查必须用 `/nova/sports-lottery/all-list?date=`。
同理传 `matchDate=` 也报同样错误。

**坑 2：错误信封统一为 `{code,message,data,timestamp,success}`**，`code` 与 HTTP 状态码一致（401/400/404），Provider 的错误分类可直接按它做。

## 接口清单（实测可用）

| 路径 | 参数 | 说明 |
|---|---|---|
| `/nova/sports-lottery/list` | 无 | 当日竞彩赛程 + 竞彩赔率 + 奖池 |
| `/nova/sports-lottery/all-list` | `date` | 按日期查赛程（推荐） |
| `/nova/tf/odds` | `matchId` / `idEvent` / `bdMatchId` + 可选 `providerId` / `marketId` | 赛前机构赔率 |
| `/nova/tf/odds-history` | 同上 | 每个选项带 `points` 赔率时间序列 |
| `/nova/tf/bookmakers` | 无 | 机构字典（实测 11 家） |
| `/nova/tf/bet-types` | 无 | 玩法字典（实测 14 种） |
| `/nova/tf/fixture-detail` | `idEvent`（**不接受 matchId**，传 matchId 报 404） | 比赛事件流 |
| `/nova/tsd/soccer-events` | `date` / `isJc` / `idLeague` / `matchId` | 四层比分 + 状态码 |
| `/nova/text/match-results` | `startDate` + `endDate`（**跨度 ≤ 30 天**） | 竞彩开奖结果 |

## 数据结构要点

### 1. 亚盘是「多档盘口 × 双边」，没有主盘标记

平博一场给出 9 档让球（`-1.25` 到 `+0.75`），每档有 `Home` / `Away` 两个 option，共 18 条。
**`main` 字段实测 640 条全为 `null`**，`suspended` 同样全空 —— 主盘、暂停状态都要自己算。

配对规则（已用抽水验证）：**同一 `line` 下的 `Home` 与 `Away` 是同一盘口的两边**，`line` 以主队视角表示（负=主队让球）。

实测抽水（`1/o1 + 1/o2 - 1`）：

| 场次 | 玩法 | 主盘（双边赔率最接近） | 该盘抽水 | 全盘抽水区间 |
|---|---|---|---|---|
| 拜仁 vs 阿森纳 | 亚盘让球 | `line=-0.25`（1.85 / 2.06） | 2.60% | 2.60%~4.03% |
| 拜仁 vs 阿森纳 | 大小球 | `line=3.0`（1.99 / 1.90） | 2.88% | 2.88%~6.35% |

这对 `asian-model-design.md` 有两层意义：

- 主盘推断规则可直接落地：`argmin |odd_home - odd_away|`。
- 多档盘口不是噪声，而是资源：可以选主盘 ± 相邻档联合反解 λ，比单盘更稳。

**接入时必须遵守的三条（阶段 B 落地时确定）**：

1. `suspended = true` 的 option 必须丢弃。V3 的 `asian_odds_snapshots` 没有该列，暂停报价会直接进入去水中位数。
2. 庄家名必须映射成稳定 ASCII 编码（平博→`pinnacle`、必发→`betfair`、威廉希尔→`williamhill`、Bet365→`bet365`、1xBet→`1xbet`、韦德→`betvictor`、利记→`liji`）。白名单按编码匹配，直接写中文会永远不命中并**静默回退全量中位数**。
3. 主盘判定以「去水概率最接近 50/50」为准，`argmin |odd_home - odd_away|` 只是它在对称盘口下的近似。2026-10-21 用仓库内 3 场抓包（events 2492881 / 2407136 / 2594567）复核，6 个市场两种口径结论一致，实现取去水口径。

### 2. 赛果口径：四层比分分离

| 字段 | 口径 |
|---|---|
| `htHomeScore / htAwayScore` | 半场 |
| `ftHomeScore / ftAwayScore` | **常规 90 分钟** |
| `etHomeScore / etAwayScore` | 含加时 |
| `penHomeScore / penAwayScore` | 点球 |
| `tfStatusShort` | `FT` / `AET` / `PEN` |

`/nova/text/match-results` 的 `sectionsNo999` 是竞彩官方开奖比分（90 分钟，含伤停补时），
`oddsResults` 按玩法拆分，`HHAD` 带 `goalLine`（实测 `-1`）、`h/d/a` 为竞彩赔率。

**注意**：`soccer-events` 的 `intHomeScore / intAwayScore` 是"实时累计"，**含加时**，不可用于结算。

### 3. 赔率历史是「变化快照」，不是定时快照

实测点数很少：当天比赛平博亚盘仅 **2 个点**（10-18 19:38、10-19 11:04），29 天前的赛事 7 个点。

这意味着：

- ✅ CLV / 收盘价可算（取最后一个点）
- ⚠️ **无法精确取"开赛前 3 小时"的价格**，只能取"≤T-3h 的最后一个点"，早期时段样本会稀疏
- 若模型强依赖定时刻赔率，需要在接入后自行定时快照（这反而是自建数据资产的起点）

## 额度核算

一次 `/nova/tf/odds?matchId=` 即可拿到全部机构×玩法（日职 70KB / 欧冠 96KB），**无需逐机构逐玩法请求**。

| 场景 | 日耗调用数 |
|---|---|
| 赛程 1 次 + 赛果 1 次 | 2 |
| 50 场 × 每 30 分钟刷新、营业 12 小时 = 24 轮 | 1,200 |
| 合计 | ≈ 1,200 / 5,000 |

30 次/分钟的限制意味着**单轮 50 场必须串行限速**（50 次调用需 ≥ 100 秒），否则触发限流。

## 风险与待办

1. **机构覆盖偏少**：日职仅 5 家（平博/威廉希尔/Bet365/1xBet/韦德），欧冠 7 家。若后续要用必发（交易所）做真实概率基准，只在大场次可用。
2. **`main` / `suspended` 恒为 null**：主盘、停盘需自算；停盘判断只能靠 `odds` 缺失或异常。入库解析必须显式丢弃 `suspended = true` 的 option（阶段 B 已在文档层面登记，代码侧等 NovaAPI Provider 接入时落地）。
3. **单次赛果查询 30 天上限**：回测取数要按月分页，一年约 12 次请求，可接受。
4. **日卡仅验证了单日**：跨日行为（赛程滚动、赛果出现时延、跨日赔率延续）仍需 3~5 天观察。
5. **数据保留总深度未定**：已验证到 2026-01，再往前未测；若做多赛季训练集，需补测。

## 接入建议（对应现有代码）

| 改动 | 位置 | 说明 |
|---|---|---|
| 新增 `NovaApiProvider` | `match/client` | Ed25519 签名 + 上述 5 个端点 |
| 补实赛果同步 | `ChinaSportteryProvider.fetchMatchResultsRaw()` 现为抛异常 | 用 `/nova/text/match-results` 替代，90 分钟口径天然正确 |
| 亚盘赔率适配 | `odds/client` | `providers → markets → options` 映射；每家每档一行完整入库（不要只留主盘）、中文庄名归一为 ASCII、丢弃 `suspended`。主盘由 `AsianMarketConsensusAggregator` 跨庄算，不在适配层决定 |
| 新增 `score_scope` 字段 | `market_result_facts` | `REGULATION_90` / `FINAL`，防止口径污染 |
| 阶段 A 结算闭环 | `SettlementWriter` / `MarketTypeEnum` | 与数据源无关，仍需先做 |

**顺序建议**：先补阶段 A 的结算闭环（纯本地数学），再接数据源。否则数据接进来预测依然停在 `LOCKED`。
