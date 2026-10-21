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
- `T613` 阶段 A 已收口（`e9c2b34`）、阶段 B 已收口（`166e87e`）、阶段 C 已收口（`0bd5450`）、阶段 D 已收口（`74a5adc`），均已推送 `feat/asian-model-v1`。
- 阶段 E 分两批：**E1~E6（V24 概率落库）已收口（`7b29b91`）**；E7~E14（统计重构、批量发布、后台亚盘展示、前端）未开始，计划文件在 `C:\Users\Admin\.workbuddy\plans\cosmic-aurora-curie-ZBnmGq5O.md`。
- E7 起的 P0 前置（**不是潜伏，是必然引爆**）：`StatisticsCalculator.recordMarket`（`:82-90`）对每条记录无条件 `orElseThrow` 要求存在 HAD/HHAD，而 ASIAN 预测的 `settlementMarkets` 只有 `ASIAN_HANDICAP`/`ASIAN_TOTAL_GOALS` → 必抛 `missing HAD history`。`StatisticsQueryServiceImpl` 无 catch，`HomeSummaryQueryServiceImpl:53` 又复用同一个 summary → **首页和统计页同时 500**。触发条件是「发布任意一条亚盘预测 + 到开赛时间」，因为 `PredictionLockJob` 自动锁定且同样不过滤 `prediction_type` —— 不需要等批量发布上线。另有 `MarketAccumulator.record` 的 switch 未处理 `PUSH`/`HALF_WIN`/`HALF_LOSS`（静默漏计）、`AdminPredictionStatusQueryServiceImpl` 硬编码只展示 HAD/HHAD。

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

## 迁移编号陷阱

- `backend/src/main/java/db/migration/` 下有 **Java 版 Flyway 迁移**（`V22__RepairLegacyNameKeyIdentityCleanup`）。新建 SQL 迁移前先 `ls` 这个目录，否则会撞号并报 `Found more than one migration with version <n>`。
- 当前最高版本：**V24**（亚盘模型概率输出 10 列）。V23 = 亚盘结算市场与 profit。
- `PostgresApplicationIT` 会断言迁移总数与末版本号，**每次加迁移必须同步改**（阶段 A 加 V23 后它红了一整个阶段没人发现，因为只跑定向 IT）。

