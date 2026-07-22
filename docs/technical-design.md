# 竞彩罗盘技术方案

## 1. 文档信息

- 文档版本：v0.3
- 文档日期：2026-07-22
- 适用范围：竞彩罗盘 MVP
- 参考项目：同级仓库 `stableflow` 的模块化单体、Flyway、MyBatis-Plus、前后端分离和任务驱动实践
- 数据库决策：使用 PostgreSQL 16

## 2. 文档目标

本文档用于固定 MVP 的技术选型和实现边界，回答以下问题：

- 后端、前端、数据库、缓存和测试采用什么技术
- 体彩源与亚盘源如何接入、映射和追溯
- 预测如何发布、锁定、生成公开快照并自动结算
- 模块、包、接口和数据库如何组织
- 定时任务如何保证幂等、重试和多实例安全
- 开发应按什么顺序推进

产品范围以 `requirements-mvp.md` 为准，数据源候选与验收标准以 `data-sources.md` 为准。本文不把未验证的数据源候选写成已完成选型。

## 3. 架构目标与原则

### 3.1 架构目标

MVP 优先打通以下闭环：

```text
体彩比赛池同步
  -> 亚盘比赛映射与盘口快照
  -> 预测生成与发布
  -> 到时锁定
  -> 官方赛果同步
  -> 自动结算
  -> 历史记录与统计公开
```

### 3.2 设计原则

- 模块化单体优先：后端保持一个 Spring Boot 应用，不拆微服务。
- 数据源职责分离：体彩源定义官方比赛池和赛果，亚盘源只提供外部分析数据。
- 原始数据先落库：供应商响应先保存，再解析为领域数据，支持重放和排障。
- 事实只追加：盘口快照、预测快照、结算和审计记录不覆盖历史事实。
- 锁定后不可变：核心预测字段在锁定后不能通过普通业务接口修改。
- 结算由事实派生：结算结果必须根据最终比赛数据计算，不能直接编辑。
- 幂等优先：同步、映射、发布、锁定、结算和快照任务均可安全重试。
- PostgreSQL 是事实源：Redis 只能做缓存、额度状态和任务锁，不能成为业务事实来源。
- 明确模型边界：请求对象使用 `Dto`，返回对象使用 `Vo`，已知结构不返回原始 JSON。
- 暂缓复杂基础设施：MVP 不引入微服务、Kafka、Kubernetes、Elasticsearch 和在线模型服务集群。

## 4. 技术选型总览

| 层级 | 选型 | MVP 版本/约束 |
| --- | --- | --- |
| JDK | Java | 21 LTS |
| 后端框架 | Spring Boot | 3.3.9，沿用当前工程 |
| Web | Spring MVC | 同步接口，不引入 WebFlux |
| 参数校验 | Spring Validation | Jakarta Validation |
| 权限 | Spring Security | 公开接口匿名，后台接口 JWT 鉴权 |
| 数据访问 | MyBatis-Plus | 3.5.7，简单查询优先使用内置 CRUD |
| 数据库 | PostgreSQL | 16.x |
| 数据迁移 | Flyway | `flyway-core` + `flyway-database-postgresql` |
| 缓存/任务锁 | Redis | 7.x |
| 外部 HTTP | Spring `RestClient` | 连接/读取超时、受控重试、额度跟踪 |
| 定时任务 | Spring Scheduling | 数据库幂等 + Redis 多实例锁 |
| API 文档 | SpringDoc OpenAPI | Swagger UI，仅开发和受控环境开放 |
| 日志与指标 | Logback + Actuator + Micrometer | traceId、任务和供应商指标 |
| 前端 | React + Vite + TypeScript | React 18.3、Vite 6、TypeScript 5.7 |
| UI | Ant Design | 5.x |
| 请求状态 | TanStack Query | 5.x |
| 路由 | React Router | 7.x |
| 后端测试 | JUnit 5 + Mockito + Testcontainers PostgreSQL | 不使用 H2 模拟 PostgreSQL 行为 |
| 外部接口测试 | WireMock | 固定供应商响应与异常场景 |
| 前端测试 | Vitest + Testing Library | 组件和数据状态测试 |
| 端到端测试 | Playwright | 覆盖核心公开链路和后台操作 |
| 开发依赖 | 云端 PostgreSQL/Redis | 本机只运行应用，不启动本地数据库和 Redis |
| 生产部署 | Docker + Nginx | 后端、前端分离部署 |

## 5. 关键选型说明

### 5.1 后端：Java 21 + Spring Boot

选择理由：

- 项目核心是状态机、定时任务、审计、幂等和统计查询，适合 Spring Boot。
- Java 21 是 LTS，当前仓库和 `stableflow` 已采用，可复用开发经验。
- 使用 Spring MVC 即可满足外部数据拉取和公开 API，不需要引入响应式编程复杂度。
- 虚拟线程可用于供应商 I/O，但不作为系统成立的前提，也不全局开启后直接忽略连接池容量。

### 5.2 数据库：PostgreSQL 16

选择 PostgreSQL 的原因：

- 当前后端已经接入 PostgreSQL 驱动和 Flyway PostgreSQL 模块，不产生额外迁移成本。
- `stableflow` 已使用 PostgreSQL 16，可复用 Flyway、MyBatis-Plus、JSONB 和测试实践。
- `JSONB` 适合保存供应商原始响应，并可在排障阶段建立表达式或 GIN 索引。
- 部分索引、检查约束、窗口函数和物化视图适合历史盘口、审计和统计分析场景。
- PostgreSQL License 宽松，Community 版本不存在功能分层或双许可证选型问题。
- PostgreSQL 16 仍处于官方支持周期，生态和托管服务成熟。

数据库统一约定：

- 数据库编码使用 UTF-8，所有环境保持相同 locale 和 collation 配置。
- 数据库和连接会话时区固定为 UTC。
- 时间字段使用 `TIMESTAMPTZ`，Java 使用 `Instant`；业务比赛日期按 `Asia/Shanghai` 计算。
- 概率使用 `NUMERIC(7,6)`，并用检查约束限制在 0～1。
- 赔率使用 `NUMERIC(10,4)`，盘口使用 `NUMERIC(6,2)`。
- 哈希使用 `CHAR(64)` 保存 SHA-256 十六进制值。
- 原始供应商响应使用 `JSONB`，但已知业务字段必须拆成显式列。
- 精确匹配的外部 ID、哈希和枚举代码不依赖模糊排序规则。
- 所有表结构变更只通过 Flyway，不启用 ORM 自动建表。
- 已执行的 migration 不修改，通过新增 migration 演进。

与当前代码的关系：

- `backend/pom.xml` 已使用 PostgreSQL 驱动和 `flyway-database-postgresql`，方向正确。
- M0 使用云端 PostgreSQL/Redis 开发实例，并补充密钥隔离、Flyway migration 和可重复测试环境。
- 测试环境使用 Testcontainers PostgreSQL，不引入只为测试存在的 H2 方言。

### 5.3 Redis

Redis 用于：

- 多实例定时任务锁。
- The Odds API 等供应商额度和短期调用状态。
- 首页、比赛列表和统计结果的短时缓存。
- 人工复核队列数量等非权威快速读数据。

Redis 不用于：

- 保存唯一版本的比赛、预测、结算或审计数据。
- 代替 PostgreSQL 事务和唯一约束实现幂等。
- 长期保存供应商原始响应。

如果 MVP 只部署一个后端实例，Redis 故障不应阻止核心数据落库；任务应拒绝无锁并发或退化为单实例安全模式，而不是产生重复事实。

### 5.4 外部数据访问

统一使用 Spring `RestClient`，不引入 WebFlux。每个 Provider 必须配置：

- base URL
- API Key 或访问凭据
- connect timeout
- read timeout
- retry max attempts
- retry delay
- enabled
- quota warning threshold

重试规则：

- 只对网络错误、超时、429 和明确的 5xx 做有限重试。
- 4xx 参数错误不自动重试。
- 429 优先尊重 `Retry-After`。
- 所有重试必须计入同步任务日志和额度统计。
- 不在 Controller 中直接调用外部供应商。

### 5.5 定时任务

P0 使用 Spring Scheduling，不引入 Quartz。原因：

- 当前任务数量少，触发规则固定。
- 任务状态和失败记录本身需要落 PostgreSQL。
- 数据库幂等约束与 Redis 锁足以支撑 MVP。

当出现动态任务、复杂日历、人工编排和大规模分片需求时再评估 Quartz。

### 5.6 前端

前端沿用 `stableflow` 已验证的组合：

- React + Vite + TypeScript
- Ant Design
- TanStack Query
- React Router

选择理由：

- 竞彩罗盘包含列表、筛选、详情、统计卡片和最小后台，Ant Design 能降低基础组件成本。
- TanStack Query 负责请求缓存、加载、失败、失效和刷新，避免自行维护重复的服务端状态。
- 当前不需要 SSR、SEO 内容生产和多端 App，因此不引入 Next.js。

## 6. 总体架构

```text
React Web
  |
  | HTTPS / JSON
  v
Spring Boot 模块化单体
  |-- Public API
  |-- Admin API
  |-- Provider Adapters
  |-- Scheduled Jobs
  |-- Prediction / Settlement Domain
  |
  +--> PostgreSQL 16 业务事实、原始数据、快照、审计
  +--> Redis 7      缓存、额度状态、任务锁
  +--> 体彩源       官方比赛池、SP、赛果
  +--> 亚盘源       亚洲让球盘与赔率
  +--> 模型入口     P0 离线文件或应用内调用
  +--> SnapshotStorage  本地文件或 S3 兼容对象存储
```

部署形态：

- 一个后端应用。
- 一个前端应用。
- 一个 PostgreSQL 实例。
- 一个 Redis 实例。
- 模型先离线计算或进程外批处理，不拆在线微服务。
- 快照存储通过接口抽象；本地开发使用文件系统，生产使用有版本能力的 S3 兼容对象存储。

## 7. 后端模块与包结构

推荐采用“顶层按业务域、模块内按技术职责”的结构：

```text
backend/src/main/java/com/jingcaicompass/
  JingCaiCompassApplication.java
  system/
    api/
    config/
    security/
    exception/
    infrastructure/
  match/
    api/
      vo/
    application/
      provider/
    domain/
    infrastructure/
      persistence/
      sporttery/
  odds/
    api/
    application/
      provider/
    domain/
    infrastructure/
      asianodds/
  prediction/
    api/
    application/
    domain/
    infrastructure/
  settlement/
    application/
    domain/
    infrastructure/
  snapshot/
    application/
    domain/
    infrastructure/
  statistics/
    api/
    application/
  audit/
    application/
    domain/
    infrastructure/
  admin/
    api/
    application/
```

约定：

- `system` 只放跨模块基础设施，不承载具体预测业务。
- Controller 只做协议转换、校验和权限入口，不编写核心规则。
- application 层 Service 使用 `XxxService` 接口和 `DefaultXxxService` 默认实现；存在明确策略含义时使用业务化实现名。
- 外部 Provider 接口放在消费方的 application 层，供应商 DTO、HTTP 客户端和适配实现放在 infrastructure 层。
- 简单 CRUD 优先使用 MyBatis-Plus；复杂统计和锁查询才编写自定义 SQL。
- 请求模型以 `Dto` 结尾，返回模型以 `Vo` 结尾。
- 枚举名称表达业务含义，例如 `MatchStatus`、`MarketType`、`SettlementStatus`，不绑定具体供应商术语。
- Provider DTO 只用于 application 与 infrastructure 的边界，不直接作为公开 API 返回值。

## 8. 数据分层

外部数据进入系统后分为四层：

1. 原始层：完整保存供应商响应、请求键、状态码和哈希。
2. 标准化层：联赛、球队、比赛、市场和来源 ID 映射。
3. 业务事实层：体彩比赛池、亚盘快照、预测、赛果和结算。
4. 展示聚合层：首页、历史和统计查询结果，可缓存但可从事实层重建。

禁止行为：

- 解析失败后丢弃原始响应。
- 用亚盘比赛替代体彩比赛池。
- 直接把供应商名称当作内部球队唯一标识。
- 为修复映射而覆盖历史盘口快照。
- 从缓存生成最终结算。

## 9. 核心数据表

P0 第一批表建议：

### 9.1 数据接入与映射

- `data_providers`：供应商配置和启停状态，不保存明文密钥。
- `raw_data_payloads`：原始响应、状态码、哈希和解析状态。
- `data_sync_runs`：同步批次、成功数、失败数、重试和额度消耗。
- `leagues`：标准联赛。
- `teams`：标准球队。
- `provider_league_mappings`：供应商联赛映射。
- `provider_team_mappings`：供应商球队映射。
- `match_source_mappings`：内部比赛与供应商比赛 ID 映射。

### 9.2 比赛与盘口

- `matches`：内部比赛、体彩编号、开赛时间、状态和最终比分。
- `sporttery_pool_snapshots`：体彩比赛池、让球、SP 和销售状态快照。
- `asian_odds_snapshots`：来源、博彩公司、盘口、主客赔率和时间戳。

### 9.3 预测与结算

- `predictions`：模型版本、概率、方向、发布时间、锁定时间和哈希。
- `prediction_snapshots`：每日公开快照和快照哈希。
- `settlements`：按预测和市场生成的结算结果。
- `audit_logs`：关键操作的追加式审计记录。

### 9.4 关键唯一约束

- `matches(lottery_match_no, lottery_date)` 唯一。
- `match_source_mappings(provider_code, external_match_id)` 唯一。
- `raw_data_payloads(provider_code, data_type, request_key, payload_hash)` 唯一。
- `asian_odds_snapshots(match_id, provider_code, bookmaker_code, captured_at, handicap_line)` 唯一。
- `predictions(match_id, model_version)` 唯一，是否允许同模型重发需由发布版本字段明确表达。
- `settlements(prediction_id, market_type)` 唯一。
- `prediction_snapshots(snapshot_date, snapshot_version)` 唯一。

所有幂等约束最终落在 PostgreSQL，不能只依赖 Java 判断或 Redis Key。

## 10. 状态模型

### 10.1 MatchStatus

- `SCHEDULED`
- `IN_PROGRESS`
- `FINISHED`
- `POSTPONED`
- `CANCELLED`
- `ABANDONED`

### 10.2 PredictionStatus

- `DRAFT`
- `PUBLISHED`
- `LOCKED`

规则：

- 只有 `DRAFT` 可以修改核心预测字段。
- `PUBLISHED` 只能在锁定时间前通过明确的重新发布流程生成新版本，不能原地静默修改。
- 到达锁定时间后必须转为 `LOCKED`。
- 锁定状态不因比赛延期自动解锁，异常情况只能追加说明并走审计流程。

### 10.3 SettlementStatus

- `PENDING`
- `HIT`
- `MISS`
- `VOID`

延期是 `MatchStatus`，在恢复并取得最终赛果前保持 `PENDING`。MVP 中亚洲盘是模型输入，不是自动结算市场，因此不使用走水、半赢或半输结算状态。

### 10.4 MappingStatus

- `PENDING`
- `AUTO_CONFIRMED`
- `MANUAL_CONFIRMED`
- `REJECTED`

## 11. 数据同步链路

### 11.1 体彩比赛池同步

1. `SportteryPoolSyncJob` 创建 `data_sync_runs`。
2. Provider Client 请求外部接口。
3. 原始响应写入 `raw_data_payloads`。
4. Adapter 转换为内部标准模型。
5. 按体彩编号和比赛日期幂等写入 `matches`。
6. 追加 `sporttery_pool_snapshots`。
7. 更新同步结果、耗时和错误信息。

### 11.2 亚盘同步

1. 根据当天竞彩池统计需要查询的联赛。
2. 读取额度状态，超出告警阈值则停止非必要刷新。
3. 请求亚盘供应商并保存原始响应。
4. 标准化联赛、球队和开赛时间。
5. 生成比赛映射候选和置信度。
6. 仅对已确认映射写入 `asian_odds_snapshots`。
7. 低置信度记录进入人工复核，不写到错误比赛。

### 11.3 赛果同步

1. 只扫描未完赛、延期或待确认的体彩比赛。
2. 保存原始赛果响应。
3. 通过允许的状态流转更新比赛。
4. 最终比分变化必须写审计并重新触发派生结算，不直接修改结算行。

## 12. 预测、锁定与快照

### 12.1 预测生成

P0 支持两种输入方式：

- 离线模型生成结构化文件，由后台导入。
- 后端调用本机或受控的模型命令入口，读取结构化结果。

不在 P0 引入长期在线 Python 推理服务。模型输出必须通过明确的 `PredictionImportDto` 进入系统并校验：

- 比赛存在且未开赛。
- 主胜、平局、客胜概率均在 0～1。
- 三项概率和在允许误差内等于 1。
- 模型版本非空且可追溯。
- 分析摘要不包含承诺收益等违规表达。

### 12.2 发布和锁定

- 发布在事务中写预测、发布时间、锁定时间、规范化内容和 SHA-256。
- 更新核心字段时使用带状态和锁定时间条件的 SQL，避免检查与更新之间的竞态。
- `PredictionLockJob` 批量锁定到期预测，重复执行不产生额外版本。
- 数据库用户权限禁止通过公开应用账号物理删除已发布预测。

### 12.3 公开快照

- 每日生成规范化 JSON manifest。
- manifest 内记录预测 ID、模型版本、发布时间、锁定时间和单条预测哈希。
- 对 manifest 计算 SHA-256，并记录到 `prediction_snapshots`。
- 文件通过 `SnapshotStorage` 保存；生产环境启用对象版本或不可覆盖策略。
- 哈希规范、字段顺序和数值格式固定，保证用户可复算。

单纯在数据库中保存一个可重新计算的哈希不构成防篡改证明，生产上线前必须补充外部可验证发布位置或可信时间戳。

## 13. 自动结算

结算流程：

1. `SettlementJob` 查询已锁定、比赛已完赛且尚未结算的预测。
2. 加载最终比分、体彩让球和对应市场规则。
3. 纯函数式计算结算结果。
4. 按 `(prediction_id, market_type)` 幂等插入结算。
5. 追加结算审计和统计刷新事件。

要求：

- 结算计算器不得访问 Controller 请求或人工输入的结果字段。
- 每种 `MarketType` 使用独立结算器并有参数化测试。
- 官方赛果修正时保留旧事实和变更记录，再重新生成派生结算版本。
- 取消、延期、中止和超时未确认必须有明确规则，不能统一当作未命中。

## 14. 定时任务与并发控制

P0 任务：

- `SportteryPoolSyncJob`
- `AsianOddsSyncJob`
- `PredictionLockJob`
- `MatchResultSyncJob`
- `SettlementJob`
- `SnapshotPublishJob`
- `StatisticsRefreshJob`

统一规则：

- 每个任务有 `enabled`、批大小、固定延迟、初始延迟和锁 TTL 配置。
- 多实例通过 Redis 锁避免同一任务同时执行。
- 数据库唯一约束作为最终幂等保护。
- 每批任务写 `data_sync_runs` 或对应任务运行记录。
- 单条失败不回滚整批成功数据，记录错误并按可重试类型处理。
- 任务锁 TTL 必须大于常规执行时间，并支持安全续期或小批次执行。

## 15. API 设计

### 15.1 公共接口

- `GET /api/public/home/summary`
- `POST /api/public/matches/list`
- `POST /api/public/matches/detail`
- `POST /api/public/history/list`
- `POST /api/public/statistics/summary`

### 15.2 后台接口

- `POST /api/admin/auth/login`
- `POST /api/admin/provider/sync-runs/list`
- `POST /api/admin/provider/mappings/list`
- `POST /api/admin/provider/mappings/confirm`
- `POST /api/admin/predictions/import`
- `POST /api/admin/predictions/publish`
- `POST /api/admin/jobs/trigger`
- `POST /api/admin/audits/list`

### 15.3 接口约定

- 请求体使用 `*Dto`，响应使用 `*Vo`。
- 统一响应外壳 `ApiResponse<T>`，分页使用 `PageResult<T>`。
- 时间统一返回 ISO 8601 UTC，同时可返回业务时区展示字段。
- 固定值域使用明确枚举，不使用无约束字符串。
- Provider 原始 JSON 只用于后台排障且必须脱敏，不作为公共响应。
- 列表接口必须限制页大小和可排序字段，禁止把字段名直接拼接到 SQL。
- Swagger UI 在生产默认关闭或仅管理员网络可访问。

## 16. 前端结构

```text
frontend/src/
  app/
  router/
  pages/
    home/
    matches/
    match-detail/
    history/
    statistics/
    admin/
  components/
  features/
    match/
    prediction/
    statistics/
    provider/
  services/
    http.ts
    public.ts
    admin.ts
  hooks/
  types/
  styles/
```

前端约定：

- API 类型显式定义，不使用 `any` 接收已知响应。
- TanStack Query 管理服务端状态，表单临时状态留在组件内。
- 公共页和后台页使用独立布局。
- 所有页面提供 loading、empty、error 和 stale 状态。
- 比赛详情明确区分体彩让球与亚洲让球盘。
- 未发布、未锁定、未结算和数据延迟状态不得伪装成正常值。

## 17. 安全与配置

### 17.1 权限

- `/api/public/**` 匿名访问，只读。
- `/api/admin/**` 使用 Spring Security + JWT。
- MVP 只提供管理员角色，不开放用户注册。
- 任务触发、映射确认和预测发布必须写操作者审计。

### 17.2 环境变量

至少包括：

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `JWT_SECRET`
- `CORS_ALLOWED_ORIGINS`
- `SPORTTERY_BASE_URL`
- `ASIAN_ODDS_PROVIDER`
- `ASIAN_ODDS_BASE_URL`
- `ASIAN_ODDS_API_KEY`
- `SNAPSHOT_STORAGE_TYPE`
- `SNAPSHOT_STORAGE_PATH`

规则：

- 仓库只提交 `application-local.example.yml` 等无密钥示例，不提交真实 `.env` 或 `application-local.yml`。
- API Key、JWT Secret 和数据库密码不写进 `application.yml` 默认值。
- 日志和后台原始响应查看接口必须脱敏认证头、Key 和 Cookie。

## 18. 可观测性

日志统一包含：

- `traceId`
- `jobName`
- `syncRunId`
- `providerCode`
- `matchId`
- `predictionId`
- `status`
- `durationMs`

核心指标：

- 体彩比赛池同步成功率和延迟。
- 亚盘覆盖率、数据延迟和月度额度消耗。
- 自动映射率、人工复核数和错误映射修正数。
- 预测发布数、待锁定数和锁定任务延迟。
- 赛果同步延迟、自动结算成功率和待处理异常数。
- Provider 4xx、429、5xx、超时和重试次数。

告警优先覆盖：

- 当日体彩比赛池为空或较历史基线异常下降。
- 亚盘覆盖率低于 90%。
- 免费额度预计提前耗尽。
- 开赛后预测仍未锁定。
- 正常完赛超过约定时间仍未结算。
- 快照生成失败或哈希不一致。

## 19. 测试策略

### 19.1 单元测试

- 球队名称标准化和映射置信度。
- 概率和校验。
- 预测状态流转与锁定规则。
- 胜平负和体彩让球胜平负结算器。
- 快照规范化和哈希计算。
- ROI、Brier Score 和 Log Loss 口径。

### 19.2 集成测试

使用 Testcontainers PostgreSQL + Redis 或独立测试容器，覆盖：

- Flyway 从空库完整迁移。
- 原始响应 -> 标准化 -> 比赛映射 -> 盘口快照。
- 发布与锁定的并发更新。
- 重复同步和重复结算幂等。
- 事务回滚和唯一约束。
- PostgreSQL JSONB、时区、索引和时间精度行为。

### 19.3 Provider 契约测试

使用 WireMock 固定以下场景：

- 正常响应。
- 空比赛池。
- 字段缺失或新增。
- 超时、429 和 5xx。
- 分页、额度不足和供应商重复比赛。
- 体彩与亚盘主客队方向不一致。

真实供应商测试使用单独 profile，默认不在普通 CI 中消耗额度。

### 19.4 前端与端到端测试

- 列表筛选、分页和异常状态。
- 详情页体彩/亚盘字段区分。
- 全量历史中包含未命中记录。
- 后台低置信度映射确认。
- 预测发布后在锁定状态不可编辑。

## 20. 开发与部署基线

### 20.1 开发依赖

- JDK 21
- Maven 3.9+
- Node.js 22 LTS
- 可访问的云端 PostgreSQL 16
- 可访问的云端 Redis 7.x

开发机只运行后端和前端，PostgreSQL 与 Redis 使用已指定的云端开发实例，不要求本地安装或启动容器。共享云端实例只用于开发运行；自动化测试必须使用隔离的 Testcontainers PostgreSQL，禁止在共享实例执行清库测试。

### 20.2 PostgreSQL 开发连接

连接信息通过未提交的 `application-local.yml` 或环境变量注入：

```text
DB_URL=jdbc:postgresql://<cloud-host>:<port>/<database>
DB_USERNAME=<username>
DB_PASSWORD=<password>
```

密码不得写入受版本控制的配置。生产环境必须按部署平台配置 TLS 和证书校验，不能直接复制开发连接参数。

### 20.3 Profile

- `local`：云端开发 PostgreSQL/Redis，配置文件忽略提交，可使用 Stub Provider。
- `test`：Testcontainers 和 WireMock，所有真实定时任务默认关闭。
- `prod`：外部配置注入，Swagger 默认关闭，真实 Provider 显式启用。

## 21. 开发执行顺序

### M0 工程基线

1. 保持 PostgreSQL 驱动和 Flyway PostgreSQL 模块，补齐版本与配置检查。
2. 添加云端开发连接示例和被忽略的 `application-local.yml`。
3. 补齐 `local/test/prod` 配置并确保密钥不进入 Git。
4. 接入 Flyway、SpringDoc、Actuator 和统一异常响应。
5. 使用 Testcontainers 让上下文测试可重复通过。

### M1 数据源验证

1. 实现 Provider 接口和 Stub Provider。
2. 建立原始响应、同步运行和额度统计表。
3. 实现体彩候选源探测。
4. 实现 The Odds API 探测。
5. 连续采集两周并形成 Go / No-Go 结论。

### M2 标准化与映射

1. 建联赛、球队和比赛表。
2. 实现标准化规则和人工别名。
3. 实现比赛候选映射和置信度。
4. 实现低置信度后台复核。

### M3 预测发布闭环

1. 建预测和快照表。
2. 实现模型结果导入校验。
3. 实现发布、锁定和哈希。
4. 生成公开快照。

### M4 赛果与结算

1. 实现赛果同步。
2. 实现各市场结算器。
3. 实现自动结算、异常和重算版本。

### M5 公共页面

1. 首页。
2. 比赛列表和详情。
3. 全量历史。
4. 统计分析。

### M6 稳定性与上线

1. 任务告警和可观测性。
2. 安全、限流和配置审计。
3. Docker/Nginx 部署。
4. 连续运行和验收。

每个里程碑都应在 `dev-tasks.md` 中拆成带状态、依赖、交付物和完成标准的任务编号。

## 22. 明确不在 MVP 引入

- 微服务拆分。
- Kafka。
- Kubernetes。
- Elasticsearch。
- Quartz。
- 在线高可用 Python 模型集群。
- 分钟级全量盘口历史抓取。
- 用户注册、会员、支付和社区系统。

## 23. 已确定与待确定事项

### 已确定

- 模块化单体。
- Java 21 + Spring Boot 3.3.9。
- MyBatis-Plus + PostgreSQL 16 + Flyway。
- Redis 7。
- React + Vite + TypeScript + Ant Design + TanStack Query。
- Spring Scheduling + 数据库幂等 + Redis 锁。
- Testcontainers PostgreSQL，不使用 H2。
- 体彩与亚盘双 Provider。

### 待数据验证后确定

- 体彩生产数据供应商。
- 亚盘生产数据供应商和博彩公司白名单。
- 开盘、临近锁定盘的具体采样时点。
- 模型进程的最终调用形式。
- 生产快照的外部可信时间戳方案。

## 24. 执行文档

- `implementation-guide.md`：定义从当前脚手架开始的文件、配置、migration、类、接口、测试和验收步骤。
- `dev-tasks.md`：使用 T001～T605 维护任务状态、依赖、交付物和完成标准。

开发时先在任务表找到任务编号，再阅读落地手册的对应里程碑。当前从 T002 开始，不并行铺开所有业务页面。
