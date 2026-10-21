# 竞彩罗盘 · 项目长期约定

## 分支与交付（2026-10-21 起，已写入 AGENTS.md 与 docs/dev-tasks.md）

- 分支前缀**不再使用 `codex/`**（那是旧工具名）。约定为 `feat/<topic>`。
- **不按功能/任务拆分支**。一个交付分支承载同一目标下的多个连续任务，分支名描述整个交付而不是第一个任务。
- 同一时刻只允许一个任务 `IN_PROGRESS`；任务 `DONE` 不触发合并，整个交付完成后才合并 Draft PR。
- 当前交付分支：`feat/asian-model-v1`（对应 T613）。

## 验证策略（用户明确要求减负）

- 不要求每次改动都跑全量集成测试。
- 纯计算/映射/校验 → 普通单测，秒级，不启动容器。
- **涉及 Flyway 迁移、PostgreSQL 专有约束/触发器 → 必须在本机真实 PG 16 容器验证一次**（这是唯一不能用"项目能启动"替代的环节，因为约束违例只在边界输入时暴露，而那次结算往往已发布不可回滚）。
- 交付收尾跑一次全量 `-Pintegration verify` + CI。

## 测试分层事实

- `mvn test`（125 个 `*Test.java`）**零 Docker**；注释里提到 Testcontainers 的 3 个文件实为静态 SQL 字符串断言。
- 只有 18 个 `*IT.java` 需要 Docker，仅在 `-Pintegration` 下执行。
- Testcontainers 版本固定 1.21.4（1.19.8 与 Docker Engine 29 的 Windows 命名管道不兼容）。
- IT 未启用 `.withReuse(true)`，冷启动约 15 秒，是"跑 Docker 很慢"的主因。

## 用户偏好

- 项目为个人自用，数据源合规问题不作为阻塞项。
- 倾向务实：能跑通优先于流程完备，但接受"关键节点必须验证"的论证。
- 中文交流，回答要有明确结论而非罗列选项。

## 已知未收口事项

- `T610` 仍为 `PARTIAL`，剩余 Draft PR + 完整 CI 证据。
- `T613` 阶段 A 已收口（`e9c2b34`）、阶段 B（`166e87e`）、阶段 C（`0bd5450`）、阶段 D（`74a5adc`）、**阶段 E 全部收口（E1~E6 `7b29b91`；E7~E17 `96001cd`→`c0718e0` 共 6 个提交）**，均已推送 `feat/asian-model-v1`。任务板状态置 `PARTIAL`，唯一缺口是 GitHub Actions + Draft PR（本机 Docker/WSL/原生 PG 全不可用，Testcontainers 跑不了）。
- 邻线校验口径（整条阶梯取最大值 vs 主盘 ±0.75）**本轮确认不改代码**，结论只写进 `docs/asian-model-design.md`，待下一阶段连同 `neighbor-tolerance` 取窗口逻辑一起处理。
- `backend/src/test/java/com/jingcaicompass/tools/NovaAsianRunTest.java` 保持未跟踪（一次性实跑脚手架，不入库）。

## 已完成的历史 P0（留档，避免重复排查）

`StatisticsCalculator.recordMarket` 曾对每条记录无条件 `orElseThrow` 要求 HAD/HHAD，亚盘预测必抛 `missing HAD history` → `HomeSummaryQueryServiceImpl` 复用同一 summary ⇒ 首页与统计页同时 500。E7 已重写为按 `predictionType` 隔离样本 + `marketsFor()` 生成市场集合；`MarketAccumulator` 的箭头 switch 已补 `default` 抛错（该 switch 是**语句**，不要求穷尽且编译不报错，新增枚举值会被静默丢弃）。

## 亚盘模型的硬约束（阶段 B、C、D 建立，后续阶段不得违反）

- **共识定线 + 真实行冻结**：决策线由跨庄聚合选出，但 `predictions.asian_odds_snapshot_id` 必须指向一条**确实报出该线**的真实快照行（复合外键 `(id, match_id)`，结算只读该行）。不要试图用共识公平价计账——公平价无法成交。
- **方向与置信度有意不同源**：方向取自**模型概率**（v5 起，由两个主盘反解 λ 后投影得到），置信度按冻结行真实报价计算。看到「共识方向明确却判低置信」不是 bug，是计账价偏高导致的真实不可交易性。
- **主盘残差不是自洽性检验**：两方程两未知数是恰定方程组，残差必然趋近 0（实测 `1e-16`）。判断市场是否打架只能看**邻线回投**（合格档位：家数 ≥2 且非主盘线）。`lineCount == 0` 要单打 `no-neighbor`，不能算通过。
- **edge 门槛只能是影子，不能真拦**：让球方向的模型概率是把 λ 投影回**同一条主盘线**，而 λ 正是由该线去水概率反解 → `edge ≡ 0`（v6 实测 3/3 场为 `0.0000`）。任何正 `safety-margin` 都会拒发 100% 的让球方向，调参无效。真判据应看 `margin = 模型概率 − 1/赔率`（含抽水），不是 `edge`。edge 唯一能非零的情形是冻结线 ≠ 主盘线（大小球常见），那是形状偏差不是 alpha。
- **σ 可以真拦，但两种 source 不可比**：σ = 跨庄去水概率的**总体**标准差，衡量"市场是否自相矛盾"，与 λ 求解无关，所以适合做硬过滤（阈值 `0.0200`，严格大于才拒，分市场独立判定）。但白名单按线生效，达标时样本常从十几家掉到两三家，σ 系统性偏小 → `source=whitelist` 与 `source=fallback` 的 σ 不能横向比较，指标必须带 `source` 标签。
- 聚合顺序固定为 **逐家去水 → 中位 → 归一**；主盘 = 偏离 50/50 最小的线（不是众数），线型偏好仅在精确同分（ε=0）时生效。
- 统一概率口径 `p_eff = (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)`，市场侧与模型侧必须逐字一致。
- **落库口径是首边，不是所选方向**：`model_cover_prob` / `market_cover_prob` / `edge_cover` 一律按主队（让球）与**大球**（大小球）记，而 `AsianPublishGate.Verdict` 里的概率**已按方向做过 `1 − p` 换算**。混用会让方向字段与概率字段互相矛盾，且统计侧无法再按首边归一。
- **新增落库列必须同时改三处**：V 版本迁移的列定义 + `protect_published_prediction()` 两处 ROW 列表（漏改等于允许事后重写已发布预测背后的概率）+ `PredictionImportDto` 的模型输出校验。列精度统一 `numeric(9,6)`，不用 5 位（生成侧 `PROBABILITY_SCALE = 6`，5 位会静默四舍五入）。
- **「值只在某个分支才算」是落库陷阱**：`overProbability` 原先只在置信度非 LOW 的分支里投影，直接落库会写入 0 而非真实概率。给任何市场新增落库列前，先确认该值在**所有分支**都被计算。

## 统计与公开验证的硬约束（阶段 E 建立）

- **两种产品线的统计不可混算**：响应形状用 `markets` 数组而**不是** `had`/`hhad` 具名字段——具名字段把「HAD/HHAD 必然存在」写进响应形状，混算风险从计算层转移到展示层。竞彩与亚盘分别请求（`predictionType`），首页固定亚盘。
- **走盘进 Brier 样本、不进命中率分母**：`outcome = HIT 1 / HALF_WIN .75 / PUSH .5 / HALF_LOSS .25 / MISS 0`；`settled = hit+miss+halfWin+halfLoss`（不含 PUSH），另给 `weightedHitRate`。只给一个命中率会在走盘比例高时骗人。
- **哈希结构只能追加不能修改**：`CanonicalPredictionV4` = V3 字段序 + 十列（6 位定标）。选版本只看事实：十列全非空 → v4、全空 → v3、部分为空抛错。`verifyStoredHash` 必须回落认「十列已填但哈希按 v3 算」的存量行，否则快照生成整批失败。manifest `schemaVersion` 取批次**最大值**，十列 `NON_NULL` 省略（纯 v3 批次字节不变）。
- **批量写操作按 `(matchId, predictionId)` 升序串行 + 逐条独立事务**：同场多条都要更新 `matches` 行，并发批次反向拿锁会死锁；一条坏数据不能回滚已成功的。结果逐条返回，且前端必须按 ID 回填（服务端会重排顺序）。
- **`switch` 语句必须补 `default` 抛错**：Java 箭头 `switch` **语句**不要求穷尽、编译不报错，新增枚举值被静默丢弃。
- **后台盘口阶梯必须复用生成侧的查询与聚合器**：自己取「最新一条快照」再算，看到的就不是模型当时用的数据，而这个页面的唯一用途就是核对它们。冻结行未必在主盘线上（只有一家报到的档位达不到最少家数）。

## 跑真实预测的前置条件（2026-10-21 实操确认）

- **亚盘快照要求比赛映射已确认**：`AsianOddsSyncServiceImpl` 对 `!isConfirmed(mapped)` 直接 `skippedUnmapped++`，而自动确认要求 score ≥ 0.85，打分只做 id / 队名的**精确相等**比较 → 中英文队名永远拿不到分。**每次跑新比赛前必须人工写 `match_source_mappings`（`MANUAL_CONFIRMED` + `MANUAL_REVIEW`），目前没有 admin 接口，只能用 SQL 或一次性运行类。** 对应地，`provider_team_mappings` 里 68 条 THE_ODDS_API 记录全是 `PENDING`，不要指望复用。
- **联赛 sport key 必须对得上赛季阶段**：`app.asian-odds.the-odds.league-sport-keys` 的欧冠要指向 `soccer_uefa_champs_league`（正赛），不是 `_qualification`；西甲需补 `soccer_spain_la_liga`。YAML 里 `"[欧冠]":` 的方括号是引号语法，map key 实际是 `欧冠`，与 `matches.league_name` 一致。
- **生成不幂等**：`generationBatchHash` 含 `generatedAt`，重跑会插入新批次。要么先删当日 DRAFT，要么导出时按 `match_id` 取 `id` 最大的一条。
- **亚盘同步是追加快照**：每次同步都写新行，冻结行会随最新报价变化，因此同一场比赛重跑后方向/置信度可能翻转。这不是 bug，但对比两次结果时必须先确认快照时间。

## 迁移编号陷阱

- `backend/src/main/java/db/migration/` 下有 **Java 版 Flyway 迁移**（`V22__RepairLegacyNameKeyIdentityCleanup`）。新建 SQL 迁移前先 `ls` 这个目录，否则会撞号并报 `Found more than one migration with version <n>`。
- 当前最高版本：**V24**（亚盘模型概率输出 10 列）。V23 = 亚盘结算市场与 profit。
- `PostgresApplicationIT` 会断言迁移总数与末版本号，**每次加迁移必须同步改**（阶段 A 加 V23 后它红了一整个阶段没人发现，因为只跑定向 IT）。

