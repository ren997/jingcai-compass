# 竞彩罗盘开发落地手册

## 1. 文档信息

- 文档版本：v0.1
- 文档日期：2026-07-22
- 适用范围：从当前脚手架开始完成竞彩罗盘 MVP
- 配套文档：`requirements-mvp.md`、`data-sources.md`、`technical-design.md`、`dev-tasks.md`

## 2. 文档目标

本文是可以按顺序执行的开发手册，用于回答：

- 从当前仓库开始，第一步具体改什么
- 云端 PostgreSQL、Redis 与本地后端、前端如何连接和启动
- Maven、前端依赖和配置文件需要补什么
- Flyway migration 如何拆分
- 第一批包、类、Dto、Vo、接口和任务如何命名
- 每个阶段运行什么测试，什么结果才算完成
- 哪些任务依赖真实数据源，哪些可以先使用 Stub 开发

执行状态以 `dev-tasks.md` 为准。本文定义实现方法，不在这里重复维护任务状态。

## 3. 当前仓库基线

### 3.1 已有内容

- Java 21 + Spring Boot 3.3.9 后端骨架。
- Spring Web、Validation、Security、Redis、MyBatis-Plus、Flyway 和 PostgreSQL 依赖。
- React 18 + Vite 6 + TypeScript 前端骨架。
- 根目录 npm 开发命令和 commitlint/husky。
- MVP、数据源和技术方案文档。
- `GET /api/public/matches` 与每日比赛列表前端纵向切片。
- `MatchQueryService` 接口、默认实现和 `SportteryProvider` 边界。
- 可配置切换的中国体彩网公开前台 Provider 与 Stub Provider。
- 公共配置支持环境变量覆盖，当前开发连接默认值按项目约定保存在 `application.yml`。

### 3.2 尚未完成

- 没有 `application-test.yml` 和 `application-prod.yml`。
- `application.yml` 尚未补齐任务、Actuator、SpringDoc、超时和重试配置。
- 没有 Flyway migration。
- 比赛池尚未持久化，没有 Mapper、Entity、原始响应和同步运行记录。
- 尚未接入亚盘 Provider、体彩赛果 Provider 和定时同步任务。
- 前端没有安装 Ant Design、TanStack Query 和测试依赖。
- 当前只有比赛列表 API 契约，没有详情、预测、历史和统计契约。
- 没有完整状态机、结算器和快照存储。

### 3.3 开发前置原则

- 先让工程在干净环境可重复启动和测试，再写业务。
- Provider 未选型完成不阻塞领域开发，先用固定样例的 Stub Provider。
- 每个阶段只打通一条纵向链路，不同时铺开全部页面。
- 新增的第三方 API Key 只通过环境变量注入，不提交仓库。
- 当前云端开发连接遵循项目已确认的直配方式；生产部署前必须迁移到部署平台密钥并轮换开发凭据。
- 自动化测试不得连接共享云端数据库；T003 完成前通过测试配置排除外部基础设施，之后统一使用 Testcontainers。
- 不修改已执行的 Flyway migration。
- 锁定、结算和审计规则必须先写测试，再接 Controller。

## 4. 开发环境

### 4.1 必需软件

- JDK 21
- Maven 3.9+
- Node.js 22 LTS
- npm 10+
- Git
- Docker Desktop 或 Docker Engine（仅供 Testcontainers 和后续部署）
- 能访问指定云端 PostgreSQL 和 Redis 的网络

检查命令：

```bash
java -version
mvn -version
node --version
npm --version
docker --version
```

Docker 仅用于后续 Testcontainers 和部署，不用于本地启动 PostgreSQL/Redis。

### 4.2 端口约定

| 服务 | 端口 |
| --- | --- |
| 前端 Vite | 5173 |
| 后端 | 8080 |
| PostgreSQL | 云端配置端口，不占用本地端口 |
| Redis | 云端配置端口，不占用本地端口 |

云端地址和凭据不写进受版本控制的文档或配置。

## 5. M0：工程基线

M0 完成后，获得云端开发实例访问权限的开发者可以运行应用；任何开发者都能使用隔离测试容器运行后端测试和构建前端。

### 5.1 开发环境配置文件

新增：

```text
backend/src/main/resources/application-local.example.yml
backend/src/main/resources/application-local.yml
backend/src/main/resources/application-prod.yml
backend/src/test/resources/application-test.yml
```

`application-local.example.yml` 只提供字段结构和占位符，复制为 `application-local.yml` 后填入开发实例信息。真实文件必须加入 `.gitignore`，不得提交。

部署和 CI 使用以下环境变量：

```text
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

DB_URL=jdbc:postgresql://<cloud-host>:<port>/<database>
DB_USERNAME=<username>
DB_PASSWORD=<password>

REDIS_HOST=<cloud-host>
REDIS_PORT=<port>
REDIS_PASSWORD=<password>

JWT_SECRET=replace-with-a-long-random-secret
CORS_ALLOWED_ORIGINS=http://localhost:5173

SPORTTERY_PROVIDER=stub
SPORTTERY_BASE_URL=
ASIAN_ODDS_PROVIDER=stub
ASIAN_ODDS_BASE_URL=https://api.the-odds-api.com
ASIAN_ODDS_API_KEY=

SNAPSHOT_STORAGE_TYPE=local
SNAPSHOT_STORAGE_PATH=./runtime/snapshots
```

若个人使用 `.env` 辅助 IDE 注入变量，该文件也必须被 `.gitignore` 排除。共享云端实例禁止执行 `clean`、`drop` 或依赖脏数据的自动化测试。

### 5.2 Maven 依赖

保留当前 PostgreSQL、Flyway 和 MyBatis-Plus 依赖，新增：

- `spring-boot-starter-actuator`
- `springdoc-openapi-starter-webmvc-ui`
- `spring-boot-testcontainers`，test scope
- `org.testcontainers:junit-jupiter`，test scope
- `org.testcontainers:postgresql`，test scope
- WireMock，test scope

JWT 依赖延迟到后台鉴权任务再加入，避免 M0 引入未使用代码。

### 5.3 application 配置

`application.yml` 只放公共结构和环境变量引用：

```yaml
spring:
  application:
    name: jingcai-compass-backend
  profiles:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD:}
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    time-zone: UTC

server:
  port: ${SERVER_PORT:8080}
```

继续补充：

- `mybatis-plus.configuration.map-underscore-to-camel-case=true`
- `springdoc.api-docs.path=/v3/api-docs`
- `springdoc.swagger-ui.path=/swagger-ui.html`
- Actuator 只公开 `health` 和 `info`
- Provider、任务和快照配置放在 `jingcai.*` 前缀下

生产配置不提供密钥默认值；开发凭据只允许出现在被忽略的 `application-local.yml`。

### 5.4 公共基础类

新增：

```text
system/api/ApiResponse.java
system/api/PageResult.java
system/exception/ErrorCode.java
system/exception/BusinessException.java
system/exception/GlobalExceptionHandler.java
system/infrastructure/TraceIdFilter.java
system/provider/ProviderException.java
system/provider/ProviderErrorCategory.java
system/config/MybatisPlusConfig.java
system/config/OpenApiConfig.java
system/security/SecurityConfig.java
```

约定：

- `ApiResponse<T>` 包含 `code`、`message`、`data`、`traceId`。
- `PageResult<T>` 包含 `records`、`pageNo`、`pageSize`、`total`。
- 业务异常使用稳定错误码，不把异常堆栈返回前端。
- traceId 进入响应头、响应体和日志 MDC。
- MyBatis-Plus 开启分页插件，并限制最大页大小。
- M0 安全配置只放行健康检查、Swagger 和后续公共只读路径，后台路径在 T601 完成前默认拒绝。
- Service 注释约定（权威全文见仓库根目录 `AGENTS.md`）：接口写简短契约；`*ServiceImpl` 与编排型 `service` 组件（如 Writer/PayloadMapper）写类职责说明，并在主流程用 `// 1) ...` 标注步骤。

### 5.5 测试数据库

不要用 H2。新增 Testcontainers PostgreSQL 测试配置：

```text
backend/src/test/java/com/jingcaicompass/support/PostgresTestContainerConfig.java
backend/src/test/resources/application-test.yml
```

要求：

- 测试容器启动 PostgreSQL 16。
- 测试 profile 运行全部 Flyway migration。
- 所有真实 Provider 和定时任务默认关闭。
- `JingCaiCompassApplicationTests.contextLoads` 在没有本机数据库的环境也能通过。

### 5.6 M0 验证命令

```bash
$env:SPRING_PROFILES_ACTIVE='local'
npm run backend:test
npm run frontend:install
npm run frontend:build
```

完成标准：

- 云端 PostgreSQL 和 Redis 的网络与认证检查通过。
- Flyway 从空库执行成功。
- 后端测试成功，不访问共享云端数据库。
- 前端构建成功并生成 `frontend/dist`。
- `/actuator/health` 返回 UP。
- `/swagger-ui.html` 在 local profile 可访问。

## 6. Flyway 迁移计划

目录：

```text
backend/src/main/resources/db/migration/
```

文件顺序：

```text
V1__init_provider_and_raw_data.sql
V2__init_league_team_match_and_mapping.sql
V3__init_sporttery_and_asian_odds_snapshots.sql
V4__init_prediction_and_public_snapshot.sql
V5__init_settlement_and_audit.sql
V6__add_core_indexes.sql
```

### 6.1 V1 Provider 与原始数据

创建：

- `data_providers`
- `raw_data_payloads`
- `data_sync_runs`

关键约束：

- `provider_code` 非空且唯一。
- 原始响应保存 `payload JSONB` 和 `payload_hash CHAR(64)`。
- `parse_status`、`sync_status` 使用受约束的业务代码。
- `(provider_code, data_type, request_key, payload_hash)` 唯一。
- 请求凭据不进入数据库原始响应或日志。

### 6.2 V2 标准实体与映射

创建：

- `leagues`
- `teams`
- `matches`
- `provider_league_mappings`
- `provider_team_mappings`
- `match_source_mappings`

关键字段：

- `matches.lottery_match_no`
- `matches.lottery_date`
- `matches.kickoff_time TIMESTAMPTZ`
- `matches.match_status`
- `matches.home_score` / `away_score`
- `match_source_mappings.mapping_status`
- `match_source_mappings.mapping_confidence NUMERIC(5,4)`

关键约束：

- `(lottery_match_no, lottery_date)` 唯一。
- `(provider_code, external_match_id)` 唯一。
- 主队和客队不能相同。
- 比分非负。
- 映射置信度限制在 0～1。

### 6.3 V3 体彩与亚盘快照

创建：

- `sporttery_pool_snapshots`
- `asian_odds_snapshots`

亚盘字段：

- `provider_code`
- `bookmaker_code`
- `handicap_line NUMERIC(6,2)`
- `home_odds NUMERIC(10,4)`
- `away_odds NUMERIC(10,4)`
- `snapshot_type`
- `captured_at TIMESTAMPTZ`
- `provider_updated_at TIMESTAMPTZ`
- `raw_payload_hash`

快照表不设置 `updated_at`，业务代码只允许 INSERT。

### 6.4 V4 预测与公开快照

创建：

- `predictions`
- `prediction_snapshots`

概率字段全部增加 0～1 检查约束。三项概率和由应用校验，并在允许的数据库能力范围内增加约束或生成校验测试。

发布后核心字段禁止普通 UPDATE；需要重发时新增发布版本，而不是覆盖旧记录。

### 6.5 V5 结算与审计

创建：

- `settlements`
- `audit_logs`

约束：

- `(prediction_id, market_type)` 唯一。
- 审计表只追加，不提供普通删除接口。
- 结算结果保存计算规则版本和输入事实版本。

### 6.6 V6 索引

至少覆盖：

- 比赛日期 + 开赛时间。
- 比赛状态 + 开赛时间。
- 预测状态 + 锁定时间。
- 赛果状态 + 待结算查询。
- 历史记录的发布时间和模型版本。
- 亚盘 `match_id + captured_at`。
- 同步运行 `provider_code + started_at`。
- 低置信度待复核映射的部分索引。

所有索引必须对应一个真实查询或任务，不为“可能有用”预建大量索引。

## 7. M1：Provider 基础能力与 Stub 闭环

M1 不等待真实供应商选型，先定义稳定内部接口并用固定样例跑通。

当前已经先落地“比赛列表查询”纵向切片，执行路径固定为：

```text
MatchController
  -> MatchQueryService
    -> MatchQueryServiceImpl
      -> SportteryProvider
        -> ChinaSportteryProvider
        -> StubSportteryProvider
```

当前切片只负责实时查询和显式 DTO 映射，不代表 M1 已完成。原始响应入库、同步运行、重试、超时、赛果和亚盘 Provider 仍按后续任务实现。

### 7.1 Provider 接口

新增：

```text
match/service/SportteryProvider.java
match/dto/SportteryMatchDto.java
odds/service/AsianOddsProvider.java
system/provider/ProviderException.java
```

建议方法：

```text
SportteryProvider.findDailyMatches(LocalDate lotteryDate)
AsianOddsProvider.fetchLeagues()
AsianOddsProvider.fetchPreMatchOdds(AsianOddsQueryDto query)
```

返回已知结构的内部 Dto，不返回 `JsonNode`。原始字符串单独交给 `RawDataPayloadService` 保存。

### 7.2 配置

新增 `@ConfigurationProperties`：

```text
match/client/SportteryProviderProperties.java
odds/client/AsianOddsProviderProperties.java
```

配置包含：

- `enabled`
- `provider`
- `baseUrl`
- `apiKey`
- `connectTimeout`
- `readTimeout`
- `retryMaxAttempts`
- `retryDelay`
- `quotaWarningThreshold`

### 7.3 Stub Provider

新增：

```text
backend/src/test/resources/sporttery/get-match-calculator-success.json
backend/src/test/resources/sporttery/sporttery-result.json
backend/src/test/resources/asianodds/asian-odds.json
```

开发 profile 可提供最小 Stub，实现：

- 3 场体彩比赛。
- 2 场有亚盘，1 场缺失用于覆盖率测试。
- 1 场球队别名不同但可自动映射。
- 1 场开赛时间冲突进入人工复核。
- 正常完赛、延期和取消各一个赛果样例。

### 7.4 原始响应入库

调用顺序必须固定：

1. 创建 `data_sync_runs`。
2. 请求 Provider。
3. 计算原始响应哈希。
4. 保存 `raw_data_payloads`。
5. 解析并写领域数据。
6. 更新同步运行结果。

解析失败不能删除原始响应，必须记录 `parse_error`。

### 7.5 M1 验证

- 同一 Stub 数据重复同步两次，业务记录不重复。
- 原始响应存在且可按 `syncRunId` 查询。
- 解析失败样例标记失败但同步任务可结束。
- 所有日志不包含 API Key。
- Provider 被 WireMock 模拟为 429/500/超时时，重试次数符合配置。

## 8. M2：比赛标准化与双源映射

### 8.1 标准化服务

新增：

```text
match/service/LeagueNormalizationService.java
match/service/TeamNormalizationService.java
match/service/MatchMappingService.java
match/service/MatchMappingReviewService.java
```

标准化顺序：

1. 精确外部 ID 映射。
2. 已确认别名映射。
3. 标准化名称匹配。
4. 联赛 + 主客队 + 开赛时间组合匹配。
5. 无法确认则创建待复核记录。

不要只用字符串相似度自动确认比赛。

### 8.2 自动映射输入

- 标准联赛。
- 主客队方向。
- 标准化球队名称。
- 开赛时间差。
- 已确认历史映射。

输出：

- 候选内部比赛 ID。
- 置信度。
- `AUTO_CONFIRMED` 或 `PENDING`。
- 可解释的匹配原因。

### 8.3 人工复核 API

第一批后台接口：

- `POST /api/admin/provider/mappings/list`
- `POST /api/admin/provider/mappings/detail`
- `POST /api/admin/provider/mappings/confirm`
- `POST /api/admin/provider/mappings/reject`

请求使用 `Dto`，响应使用 `Vo`，确认和拒绝写审计日志。

### 8.4 M2 验证

- 主客队反转不会自动确认。
- 开赛时间超出容差不会自动确认。
- 人工确认后再次同步可直接复用映射。
- 相同外部比赛不能映射到多个内部比赛。
- 覆盖率、自动映射率和待复核数可统计。

## 9. M3：预测导入、发布、锁定和快照

### 9.1 首批枚举

- `MatchStatusEnum`
- `PredictionStatus`
- `ConfidenceLevel`
- `MarketType`
- `SnapshotType`
- `MappingStatus`
- `SyncStatus`

枚举使用供应商无关的业务含义。

### 9.2 首批 Dto / Vo

请求：

- `PredictionImportDto`
- `PredictionPublishDto`
- `PredictionListQueryDto`
- `PredictionDetailQueryDto`

响应：

- `PredictionListItemVo`
- `PredictionDetailVo`
- `PredictionPublishResultVo`
- `PredictionSnapshotVo`

### 9.3 服务

新增：

```text
prediction/service/PredictionImportService.java
prediction/service/PredictionPublishService.java
prediction/service/PredictionLockService.java
snapshot/service/PredictionSnapshotService.java
snapshot/storage/SnapshotStorage.java
snapshot/storage/LocalSnapshotStorage.java
```

发布事务：

1. 锁定比赛行或读取带版本条件的比赛。
2. 校验未开赛且允许发布。
3. 校验模型版本和概率。
4. 生成规范化预测内容。
5. 计算 SHA-256。
6. 写预测和审计。
7. 返回发布时间、锁定时间和哈希。

### 9.4 锁定

核心更新必须带条件：

```text
prediction_status = PUBLISHED
lock_time > current_timestamp
```

`PredictionLockJob` 重复运行时只处理仍为 `PUBLISHED` 且已到锁定时间的记录。

### 9.5 快照

规范化 JSON 必须固定：

- UTF-8。
- 字段顺序。
- 小数格式。
- 时间格式。
- 空值策略。
- 排序规则。

快照测试必须证明同一事实重复生成得到相同哈希。

### 9.6 M3 验证

- 概率越界或概率和不合法时拒绝导入。
- 已开赛比赛不能发布。
- 锁定前允许明确的版本化重发，旧版本仍保留。
- 锁定后并发修改全部失败。
- 快照文件、数据库哈希和重新计算结果一致。

## 10. M4：赛果同步与自动结算

### 10.1 结算器接口

新增：

```text
settlement/service/MarketSettlementCalculator.java
settlement/service/WinDrawLossSettlementCalculator.java
settlement/service/SportteryHandicapSettlementCalculator.java
settlement/service/SettlementService.java
```

每个计算器接收明确事实对象，返回结算结果，不直接查数据库。

### 10.2 结算测试矩阵

胜平负：

- 主胜、平、客胜。

体彩让球胜平负：

- 正让球、负让球和零让球。
- 让球后胜、平、负。

异常：

- 延期。
- 取消。
- 中止。
- 最终比分待确认。
- 官方赛果修正。

### 10.3 结算任务

`SettlementJob` 只处理：

- 预测已锁定。
- 比赛已确认完赛。
- 对应市场尚未存在最终结算。

同一预测和市场重复处理必须由唯一约束挡住重复记录。

### 10.4 M4 验证

- 所有结算矩阵由参数化测试覆盖。
- 人工不能直接提交结算结果。
- 赛果修正生成新事实版本和审计记录。
- 结算任务失败可重试，不重复结算。

说明：亚洲让球盘在 MVP 中是模型输入和分析数据，不作为自动结算市场。后续若增加亚洲盘预测产品，必须另行增加半赢、半输、走水和拆分投注规则。

## 11. M5：公共 API 与前端

### 11.1 公共 API

- `GET /api/public/home/summary`
- `POST /api/public/matches/list`
- `POST /api/public/matches/detail`
- `POST /api/public/history/list`
- `POST /api/public/statistics/summary`

每个接口先定义 Dto/Vo 和示例响应，再实现 Controller。

### 11.2 前端依赖

新增生产依赖：

- `antd`
- `@tanstack/react-query`

新增开发依赖：

- `vitest`
- `@testing-library/react`
- `@testing-library/jest-dom`
- `jsdom`
- Playwright

### 11.3 前端基础设施

新增：

```text
src/router/index.tsx
src/services/http.ts
src/services/public.ts
src/services/admin.ts
src/types/api.ts
src/app/PublicLayout.tsx
src/app/AdminLayout.tsx
```

`http.ts` 统一处理：

- base URL。
- traceId。
- 非 2xx 错误。
- 登录失效。
- 可取消请求。

### 11.4 页面顺序

1. 比赛列表。
2. 比赛详情。
3. 历史记录。
4. 首页汇总。
5. 统计页。
6. 最小后台映射复核和任务运行页。

先完成列表到详情的纵向链路，再做首页视觉包装。

### 11.5 M5 验证

- 全量历史包含未命中记录。
- 体彩让球和亚洲盘有独立标签与字段。
- loading、empty、error、stale 状态完整。
- 筛选、分页和刷新不会产生重复请求风暴。
- 移动端窄屏可阅读，不要求开发独立 App。

## 12. M6：后台鉴权、可观测性和部署

### 12.1 后台鉴权

- Spring Security + JWT。
- MVP 仅管理员登录，不开放注册。
- 公共接口只读匿名。
- 任务触发、映射确认、预测发布和异常操作必须审计。

### 12.2 任务监控

后台至少展示：

- 最近同步运行。
- Provider 成功率、延迟、429 和剩余额度。
- 待复核映射。
- 待锁定预测。
- 待结算比赛。
- 快照失败记录。

### 12.3 部署

- 后端 Dockerfile 使用 JRE 21 运行时镜像和非 root 用户。
- 前端多阶段构建后由 Nginx 托管。
- 生产 PostgreSQL 和 Redis 优先使用托管或独立实例。
- 数据库迁移在单一受控实例执行，避免多副本同时迁移。
- 快照目录或对象存储必须持久化和备份。

### 12.4 M6 验证

- 从空环境按部署文档启动成功。
- 备份恢复演练通过。
- Provider 和结算任务异常触发告警。
- Swagger、Actuator 和后台接口不向公网裸露。
- 日志无密钥、密码、Authorization 和完整 Cookie。

## 13. 分支与提交建议

按可独立验证的业务能力提交，不按文件类型拆碎。建议提交顺序：

```text
chore(repo): 增加PostgreSQL与Redis本地环境
chore(backend): 补齐配置分层与测试容器
feat(provider): 新增双数据源适配与原始数据入库
feat(match): 新增比赛标准化与映射流程
feat(prediction): 新增预测发布锁定与公开快照
feat(settlement): 新增赛果同步与自动结算
feat(public): 打通比赛历史与统计前后端
feat(admin): 新增数据任务监控与映射复核
```

提交前至少执行当前改动相关的测试和 `git diff --check`。

## 14. 通用 Definition of Done

一个任务只有同时满足以下条件才能标记 `DONE`：

- 交付物已进入正确目录。
- 核心规则有单元测试。
- 数据库改动有新增 Flyway migration。
- API 使用明确 Dto/Vo 并有校验。
- 已知响应结构没有暴露原始 JSON。
- 日志不包含凭据。
- 幂等和异常路径已验证。
- 相关文档和 `dev-tasks.md` 状态已更新。
- Maven 测试或前端构建通过。
- 没有把未解决失败以跳过测试的方式隐藏。

## 15. 每日开发检查清单

开始任务前：

1. 在 `dev-tasks.md` 找到任务编号、依赖和完成标准。
2. 阅读本文对应里程碑。
3. 检查依赖任务是否真实完成，而不是只看文档状态。
4. 查看工作区是否存在他人未提交修改。

完成任务后：

1. 运行最小相关测试。
2. 运行完整后端测试或前端构建。
3. 检查 migration、配置和密钥。
4. 更新任务状态与实际交付物。
5. 使用仓库提交格式提交。

## 16. 现在应执行的第一项任务

从 `dev-tasks.md` 的 `T002` 开始：

1. 补齐 Actuator、SpringDoc 和测试依赖。
2. 补齐 Provider、任务、Flyway、Redis 和 API 文档配置。
3. 新增不含默认密钥的 `application-prod.yml`。
4. 验证配置可以通过环境变量覆盖。

T002 完成后进入 T003，使用 Testcontainers PostgreSQL 修复 `contextLoads`，确保自动化测试不访问共享云端数据库。
