# NovaAPI 数据源验证探针

自用场景下验证 `novaapi.tsgnkj.com` 能否替代现有数据源。**不接入产品代码**，只做只读探测，产物是原始 JSON。

> 2026-10-21 已用日卡凭证实跑通过，结论见 `docs/data-sources-novaapi.md`。

## 运行

```bash
cd tools/novaapi-probe
export MSYS_NO_PATHCONV=1          # Windows/Git Bash 必需，否则 /nova/... 会被改写成 Windows 路径

# 1. 先做自检（无需凭证，验证 Ed25519 签名链路与网络可达）
java -Dstdout.encoding=UTF-8 NovaApiProbe.java selftest

# 2. 拿到 Key 与私钥后，跑全量探测（13 个请求）
export NOVA_API_KEY=你的APIKey
export NOVA_PRIVATE_KEY_B64=你的PKCS8私钥base64
java -Dstdout.encoding=UTF-8 NovaApiProbe.java all

# 3. 任意接口单点验证：raw <path> [k=v,k=v]
java -Dstdout.encoding=UTF-8 NovaApiProbe.java raw "/nova/tf/odds" "matchId=2042115"
```

凭证只从环境变量或参数读取，**不落盘**。也可用 `--private-key-file=path` 指向本地私钥文件（该路径已被 `.gitignore` 忽略）。
若环境需要代理，探针会自动读取 `HTTPS_PROXY` / `HTTP_PROXY`。

产物目录 `novaapi-probe-out/<时间戳>/`：每个请求一个 `.json`（原始响应）+ `.headers.txt`。

## 实测踩过的两个坑

1. **`/nova/sports-lottery/list` 不接受任何参数**。传 `date=` / `matchDate=` 会让签名校验失败并返回 401（服务端只把 apiKey/timestamp 纳入验签）。按日期查要走 `/nova/sports-lottery/all-list?date=`。
2. **`/nova/tf/fixture-detail` 只认 `idEvent`**，传 `matchId` 返回 404 `未找到比赛明细数据`。

## 探测的 13 项及判读标准

| # | 请求 | 要回答的问题 | 通过标准 |
|---|---|---|---|
| 01 | `/nova/sports-lottery/list?date=` | 竞彩赛程能否拿到、`matchId` 是否贯穿 | 返回当日场次，`matchId` 非空 |
| 02 | `/nova/tf/odds?matchId=` | 省略 `providerId`/`marketId` 是否返回全部机构×玩法 | 一次调用含 10+ 机构、含亚盘 marketId=4 与大小球 5 |
| 03 | `/nova/tf/odds?matchId=&providerId=4&marketId=4` | 过滤参数是否生效 | 只返回平博 + 让球，且带 `line` |
| 04 | `/nova/tf/odds-history?matchId=&providerId=4&marketId=4` | 赔率时间序列是否可用（CLV/收盘价） | `points` 数组长度 > 0 |
| 05/06 | `/nova/tf/bookmakers`、`/nova/tf/bet-types` | 机构与玩法字典 | 含 providerId=4 平博、marketId=4/5 |
| 07~09 | `/nova/text/match-results?startDate=&endDate=` | 赛果回溯深度 | 5/30/90 天各有多少条，据此定补数窗口 |
| 10/11 | `/nova/tf/odds`、`/nova/tf/odds-history`（用 30 天前的 matchId） | **回测可行性：已结束赛事的历史赔率还在不在** | `points` > 0 才能回测；为 0 则只能向前攒快照 |
| 12 | `/nova/tsd/soccer-events?date=`（30 天前） | 历史日期能否枚举赛事 + 比分口径是否分离 | 返回条数 > 0，且 `ftHomeScore` 与 `etHomeScore` 同时存在 |
| 13 | `/nova/tf/fixture-detail?matchId=` | 事件流能否独立还原 90 分钟比分 | `events[]` 含 `elapsedMin` + `type=Goal` |

## 比分口径：90 分钟与加时是分开的（已确认）

预测按 90 分钟算，这里不存在歧义——接口提供了四层比分字段，加时与点球各自独立：

| 接口 | 字段 | 口径 |
|---|---|---|
| `/nova/tsd/soccer-events` | `ftHomeScore / ftAwayScore` | **常规 90 分钟**（文档原文标注） |
| 同上 | `etHomeScore / etAwayScore` | 含加时赛 |
| 同上 | `penHomeScore / penAwayScore` | 点球大战 |
| 同上 | `tfStatusShort` | `FT` 常规时间结束 / `AET` 加时结束 / `PEN` 点球结束 |
| `/nova/text/match-results` | `sectionsNo999` | 竞彩官方开奖比分，按竞彩规则即 90 分钟（含伤停补时） |
| `/nova/tf/fixture-detail` | `events[].elapsedMin` + `type=Goal` | 可累加 90 分钟内进球，作为第二重校验 |

**唯一的坑**：`/nova/tsd/soccer-events` 的 `intHomeScore / intAwayScore` 描述是"实时累计"，**含加时**。拿它结算会算错淘汰赛，必须用 `ftHomeScore`。

推荐结算链路（对应 `AGENTS.md` 的"结算结果必须由最终比赛数据派生"）：

1. 取 `ftHomeScore/ftAwayScore` 作主口径，与 `sectionsNo999` 交叉比对；
2. 若 `tfStatusShort ∈ {AET, PEN}`，标记该场"进过加时"，仍按 90 分钟结算但进人工复核队列；
3. 从 `fixture-detail` 累加 `elapsedMin ≤ 90` 的 Goal 事件，与 ft 比分三方对齐，不一致则拒绝自动结算。

推论：`market_result_facts` 应落一个 `score_scope`（`REGULATION_90` / `FINAL`）字段，否则历史数据无法区分来源口径。

## 赛果接口在哪（找不到的原因）

文档导航里没有「赛果」二字，实际路径是 **竞足 → 查询赛后开奖信息**，对应 `/nova/text/match-results`。

参数只有两个，且都可空：`startDate`、`endDate`（`yyyy-MM-dd`）。注意文档页的参数表可能同时显示 `matchId` 和 `messageType`，那两个属于紧邻的「DeepSeek深度推理」接口（`messageType` 是"预测比分：1-正常/2-高赔率"），**不是赛果接口的参数**，别误传。

返回结构（示例）：

```json
{"code":200,"message":"操作成功","data":{
  "startDate":"2026-03-02","endDate":"2026-03-02",
  "results":[{"matchId":2037977,"homeTeam":"大田市民","awayTeam":"安养FC",
    "leagueId":48,"sectionsNo1":"1:0","sectionsNo999":"2:1","winFlag":"H",
    "h":"2.05","d":"3.20","a":"3.35","goalLine":"-1",
    "oddsResults":[{"code":"HAD","combination":"H","odds":2.05}]}]}}
```

## 回测需要三样东西，别混为一谈

| 要素 | 接口 | 状态 |
|---|---|---|
| 历史**赛果**（标签） | `/nova/text/match-results` | 有，深度待测（07~09） |
| 历史**赛前赔率**（特征） | `/nova/tf/odds-history` | **未知**，是关键门槛（10/11） |
| 历史**赛事列表**（枚举样本） | `/nova/tsd/soccer-events?date=` | 支持按日查，历史日期是否可查待测（12） |

赛果只能当标签用。模型要的是"那场开赛前 3 小时平博的让球盘是多少"，这只能来自 `odds-history` 的 `points` 时间序列。文档全站**没有任何数据保留期限说明**（搜遍"保留/仅支持/最多返回/回溯/过期"都没有），所以必须实测第 10/11 步。

如果第 11 步 `points` 为空，结论就是：**历史赔率不可回溯，回测只能从接入当天起向前攒快照**，攒够样本前无法验证模型——这会直接改变项目排期。

## 日卡当天的 GO / NO-GO 判定

日卡 ¥5、月卡 ¥100（约 ¥3.33/天）。用一张日卡买"要不要长期用"的结论，是最划算的一步。当天只需看三个判定：

| 判定项 | 对应步骤 | GO | NO-GO |
|---|---|---|---|
| 数据可用 | 01~06 | 赛程、亚盘让球、大小球都能拿到且带 `line` | 拿不到亚盘或 `line` 恒为空 → 整套方案不成立 |
| 回测可行 | 11 | `points` 非空，历史赔率可回溯 | 为空 → 回测只能从接入日起攒，排期要推后数周 |
| 口径正确 | 12 | `ftHomeScore` 存在，且 `tfStatusShort` 有 `FT/AET/PEN` | 只有含加时比分 → 淘汰赛结算会错 |

三项全 GO → 直接买月卡；任一 NO-GO → 只损失 ¥5，且拿到了明确的排除理由。

一张日卡**测不出**的东西（只能靠时间积累，不影响买月卡的决策）：快照累积速度、CLV 序列稳定性、实际日耗额度是否逼近 5000。

**买日卡前先问客服一句话**：日卡与月卡的接口权限、限速、额度是否完全一致（尤其 `tf/odds-history` 和 DeepSeek 系列是否在日卡档被裁剪）。若日卡是阉割版，验证结论会失真——这是日卡方案唯一的实质风险。

## 关键判读点

1. **额度模型**：若第 02 步一次能拿全，一场 1 次调用即够（5000 次/日可覆盖 5000 场）；若必须逐机构逐玩法传，成本放大 20 倍，方案要重算。响应头无 `x-ratelimit-*`，剩余额度服务端不返回，只能靠日志自查。
2. **四分之一盘**：脚本末尾会汇总所有 `line` 取值并标注是否含 `.25`/`.75`。没有四分之一盘意味着 `docs/asian-model-design.md` 的拆盘结算逻辑要改。
3. **赛果回溯**：`07~09` 三档条数若一致且都不为 0，说明可长回溯，能直接做回测；若只有 5 天有数据，模型训练集仍需另找（football-data.co.uk）。
4. **赛果口径**：`sectionsNo999` 是竞彩官方全场比分（90 分钟口径），这正是亚盘结算需要的，也是它优于 The Odds API 的核心点。

## 已知的鉴权行为（已实测）

用临时密钥对发请求，服务端返回：

```json
{"code":401,"message":"API密钥无效或已过期","data":null,"timestamp":1792551202215,"success":false}
```

说明：统一响应信封为 `{code,message,data,timestamp,success}`；服务端**先校验 API Key 再校验签名**，所以拿到真 Key 之前无法区分「Key 错误」与「签名算法错误」。真 Key 到手后若仍 401 或 403，优先对照签名串规则：`apiKey={key}&timestamp={ts}` + 其余参数按 key 升序拼接，Ed25519 签名后 base64 放入 `X-Signature`。
