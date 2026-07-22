# 竞彩罗盘开发执行手册与任务看板

## 0. 文档状态

- 文档版本：v0.2
- 最后更新：2026-07-22
- 作用：本项目唯一的开发顺序、任务状态和验收记录入口
- 当前活动任务：无
- 下一任务：`T005 前端依赖与测试基线`
- 最近完成增量：`T004 公共后端基础设施`

> 开始任何功能开发前先更新本文件；提交代码时必须同时提交对应任务状态、步骤勾选和验证记录。若本文件与 `implementation-guide.md` 的执行顺序冲突，以本文件为准；架构规则仍以 `technical-design.md` 为准。

## 1. 目标

本文件既是 MVP 执行看板，也是可以从当前仓库开始逐项执行的开发手册。每个任务都包含依赖、执行步骤、产物、验证命令和完成标准，不允许只凭“代码看起来完成”修改状态。

当前主链路：

```text
工程基线
  -> 双 Provider 与原始数据
  -> 比赛标准化和映射
  -> 预测发布、锁定和公开快照
  -> 赛果同步和自动结算
  -> 历史与统计公开
  -> 后台监控和上线
```

## 2. 每次开发必须执行的流程

### 2.1 开始任务

1. 阅读任务的依赖、执行步骤、交付物和完成标准。
2. 确认所有依赖任务为 `DONE`；经项目负责人明确决定不执行的依赖可标记为 `SKIPPED`，但必须记录原因、风险和替代验证方式。原型纵向切片必须在任务中明确记录例外，不能当作依赖已经完成。
3. 将目标任务改为 `IN_PROGRESS`，并更新“当前活动任务”。
4. 在任务的“执行记录”中写入开始日期、本次范围和预计验证命令。
5. 开始写代码。同一时间只能有一个 `IN_PROGRESS` 任务。

### 2.2 开发过程中

1. 严格按任务步骤顺序实施；每完成一步，将 `[ ]` 改为 `[x]`。
2. 新发现的范围如果属于当前任务，追加步骤；如果属于新需求，先更新 `requirements-mvp.md`，再新增任务。
3. 发现依赖缺失时停止扩展当前任务：可回到依赖任务，或把当前任务标记为 `BLOCKED` 并写明解除条件。
4. 任何已知响应结构使用显式 `Dto`/`Vo`，不把供应商原始 JSON 暴露给 Controller。
5. 数据库结构只通过新的 Flyway migration 修改，禁止手工改表后补文档。

### 2.3 结束任务或增量

1. 运行任务列出的验证命令，并把实际结果写入“验证记录”。
2. 运行通用检查：

   ```bash
   npm run backend:test
   npm run frontend:build
   git diff --check
   ```

   仅修改单端且另一端明确不受影响时，可以不重复运行另一端命令，但必须在验证记录说明原因。
3. 全部步骤和完成标准满足后改为 `DONE`。
4. 只完成可独立验证的部分增量时改为 `PARTIAL`，写明已完成内容和恢复时的第一步。
5. 外部条件阻塞时改为 `BLOCKED`，写明阻塞证据、解除条件和可继续的替代任务。
6. 更新里程碑表、“当前活动任务”“下一任务”和文档底部的变更记录。
7. 代码与本文件一起提交，提交信息遵循 `<type>(<module>): <中文主题>`。

### 2.4 状态定义

- `TODO`：未开始。
- `IN_PROGRESS`：当前正在执行；全项目只能有一个。
- `PARTIAL`：已有可验证增量，但任务未达到全部完成标准，当前没有继续执行。
- `BLOCKED`：有明确外部阻塞，并在任务下说明原因。
- `SKIPPED`：项目负责人明确决定不执行；必须记录原因、已接受风险和后续替代验证方式。
- `DONE`：代码、测试、文档和完成标准均已满足。

### 2.5 文档职责

- `requirements-mvp.md`：定义做什么、不做什么和业务验收口径。
- `technical-design.md`：定义技术栈、架构边界、数据模型和不可违反的规则。
- `implementation-guide.md`：提供较长的实现示例、建表建议和编码说明。
- `data-sources.md`：记录供应商证据、覆盖率、授权与 Go / No-Go 结论。
- `dev-tasks.md`：决定现在做哪一项、按什么顺序做、做到什么程度和如何验证。

## 3. 全量执行顺序

默认严格按下列阶段推进；括号内任务可在其依赖完成后穿插，但不能绕过 `DONE` 依赖：

```text
阶段 A 工程底座
T000 -> T001 -> T002 -> T003(SKIPPED) -> T004 -> T005

阶段 B Provider 与原始数据
T101 -> T102 -> T103 -> T104 -> T105
                         -> T106（连续观测）
                         -> T107（连续观测）

阶段 C 比赛标准化与双源映射
T201 -> T202 -> T203 -> T204 -> T205 -> T206

阶段 D 预测发布闭环
T301 -> T302 -> T303 -> T304 -> T305

阶段 E 赛果与结算
T401 -> T402 -> T403 -> T404 -> T405

阶段 F 公共产品
T501 -> T502 -> T503 -> T504 -> T505

阶段 G 上线
T108 + T601 -> T602 -> T603 -> T604 -> T605
```

说明：此前为验证产品和数据源提前完成了比赛列表纵向切片，因此 T101、T103、T106、T501、T503 当前为 `PARTIAL`。这些增量不改变主线依赖；T002 已补齐，T003 经项目负责人决定跳过，恢复正式开发时从 T004 继续补齐底座。

## 4. 当前进度

| 里程碑 | 状态 | 说明 |
| --- | --- | --- |
| M0 工程基线 | `PARTIAL` | T004 公共后端基础设施已完成；T003 跳过，下一步完成 T005 |
| M1 Provider 基础 | `PARTIAL` | 体彩查询契约、真实适配器和最小 Stub 已完成，等待 M0 底座 |
| M2 标准化与映射 | `TODO` | 依赖基础表和 Provider 契约 |
| M3 预测发布闭环 | `TODO` | 可使用 Stub 比赛数据开发 |
| M4 赛果与结算 | `TODO` | 依赖锁定预测和最终赛果 |
| M5 公共 API 与前端 | `PARTIAL` | 比赛列表纵向切片已完成，等待正式主线依赖 |
| M6 后台、稳定性与上线 | `TODO` | 真实上线受数据源选型阻塞 |

## 5. M0 工程基线

### T000 技术与开发文档基线

- 状态：`DONE`
- 优先级：P0
- 依赖：无
- 交付物：
  - `docs/requirements-mvp.md`
  - `docs/data-sources.md`
  - `docs/technical-design.md`
  - `docs/implementation-guide.md`
  - `docs/dev-tasks.md`
- 执行步骤：
  - [x] 明确 MVP 范围、非目标和验收指标。
  - [x] 固定技术栈、模块边界和数据库原则。
  - [x] 记录体彩源、亚盘源和双源映射方案。
  - [x] 编写实施指南和编号化任务。
  - [x] 将本文件升级为唯一执行看板并补充状态更新规则。
- 验证命令：
  - `git diff --check`
  - 人工检查五份文档的链接、术语和任务编号一致。
- 完成标准：
  - 技术栈固定为 PostgreSQL 16、Redis 7、Spring Boot 和 React/Vite。
  - 数据源验证与业务开发边界明确。
  - M0～M6 有编号化任务和完成定义。
- 验证记录：
  - 2026-07-22：文档基线及可执行任务看板完成。

### T001 云端 PostgreSQL 与 Redis 开发接入

- 状态：`DONE`
- 优先级：P0
- 依赖：T000
- 交付物：
  - `application-local.example.yml`
  - Git 忽略的 `application-local.yml`
  - `.gitignore` 中的本机密钥配置规则
- 执行步骤：
  - [x] 验证 PostgreSQL 和 Redis 云端端口可达。
  - [x] 验证开发账号认证和应用连接。
  - [x] 在 `application.yml` 提供项目确认的开发默认值和环境变量覆盖。
  - [x] 明确应用不会自动创建 PostgreSQL 数据库。
  - [x] 测试上下文排除共享 PostgreSQL、Redis 和 Flyway。
- 验证命令：
  - `npm run backend:test`
  - 启动应用并确认数据源、Redis 与 Flyway 初始化日志无认证错误。
- 完成标准：
  - 云端 PostgreSQL 和 Redis 的端口可访问且认证成功。
  - 开发连接可由环境变量覆盖，生产部署不依赖开发默认值。
  - 生产上线前有明确的凭据迁移和轮换动作。
  - 自动化测试明确禁止使用共享云端数据库。
- 验证记录：
  - 2026-07-22：云端连接和应用启动已人工验证；测试上下文未连接共享服务。

### T002 后端依赖与配置分层

- 状态：`DONE`
- 优先级：P0
- 依赖：T001
- 交付物：
  - 补齐 `backend/pom.xml`
  - `application.yml`
  - `application-local.example.yml`
  - `application-prod.yml`
  - Provider、任务、Redis、Flyway、Actuator 和 SpringDoc 配置
- 执行步骤：
  - [x] 在 `pom.xml` 引入 Web、Validation、Security、Redis、MyBatis-Plus、Flyway 和 PostgreSQL。
  - [x] 设置 Java 21 编译版本。
  - [x] 为 PostgreSQL、Redis、端口和体彩 Provider 提供环境变量覆盖。
  - [x] 新增 `application-prod.yml`，生产配置只引用环境变量且不含开发默认凭据。
  - [x] 将 Provider、同步任务、超时、重试和额度阈值改为 `@ConfigurationProperties`。
  - [x] 引入并配置 Actuator 与 SpringDoc，明确 local/prod 暴露范围。
  - [x] 增加配置绑定测试，覆盖缺少必填生产变量和非法超时值。
  - [x] 更新 `application-local.example.yml` 与 README 启动说明。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml dependency:tree
  npm run backend:test
  npm run backend:run
  ```

- 执行记录：
  - 2026-07-22：完成基础依赖、Java 21 和开发连接配置；因先验证产品纵向切片暂停，状态记为 `PARTIAL`。
  - 2026-07-22：恢复执行；范围为 prod 配置、配置属性、HTTP 超时、Actuator、SpringDoc、配置测试与说明文档。
- 完成标准：
  - 配置通过环境变量覆盖。
  - 生产配置没有密钥默认值。
  - PostgreSQL 驱动和 Flyway PostgreSQL 模块可解析。
  - local profile 能连接云端开发 PostgreSQL 和 Redis。
- 验证记录：
  - 2026-07-22：基础依赖、Java 21、云端开发配置和环境变量覆盖已通过编译及启动验证。
  - 2026-07-22：`mvn test` 共 10 个测试通过；Actuator/OpenAPI 冒烟测试、配置校验和生产变量保护测试通过；`npm run build` 与依赖解析通过。

### T003 Testcontainers 与上下文测试

- 状态：`SKIPPED`
- 优先级：P0
- 依赖：T002
- 交付物：
  - PostgreSQL Testcontainer 测试配置
  - `application-test.yml`
  - 修复后的 `JingCaiCompassApplicationTests`
- 执行步骤：
  - [ ] 增加 Testcontainers PostgreSQL 与 JUnit 依赖。
  - [ ] 创建 `application-test.yml`，固定关闭真实 Provider、Redis 外联和定时任务。
  - [ ] 建立共享 PostgreSQL Container 测试基类或 Spring `@ServiceConnection` 配置。
  - [ ] 让 Flyway 在容器空库执行全部 migration。
  - [ ] 恢复完整应用上下文测试，不再通过排除 DataSource/Flyway 通过。
  - [ ] 增加保护性断言，确保测试 JDBC URL 指向容器而非云端地址。
- 验证命令：

  ```bash
  docker version
  npm run backend:test
  ```

- 跳过记录：
  - 2026-07-22：项目负责人决定不在本地安装 Docker，因此本任务不执行。
  - 已接受风险：自动化测试无法从 PostgreSQL 空库验证全部 Flyway migration，也无法覆盖完整数据库上下文启动。
  - 替代约束：普通自动化测试继续排除 DataSource/Flyway，严禁连接共享云数据库；migration 在后续开发中通过云端开发环境启动日志和人工检查验证。

- 完成标准：
  - 不启动本机 PostgreSQL 也能运行测试。
  - 测试 profile 关闭真实 Provider 和定时任务。
  - Flyway 能在测试容器空库执行。
  - `npm run backend:test` 通过。

### T004 公共后端基础设施

- 状态：`DONE`
- 优先级：P0
- 依赖：T002
- 交付物：
  - `ApiResponse`
  - `PageResult`
  - 全局异常处理
  - 错误码
  - traceId Filter
  - MyBatis-Plus 分页配置
  - SpringDoc 配置
  - M0 最小安全配置
- 执行步骤：
  - [x] 定义统一 `ApiResponse<T>`、`PageResult<T>` 和错误码枚举。
  - [x] 实现参数校验、业务异常和未知异常的全局处理。
  - [x] 增加 traceId Filter，并把 traceId 同步写入日志上下文和响应头。
  - [x] 配置 MyBatis-Plus 分页上限与公共审计字段处理。
  - [x] 配置 SpringDoc、Actuator 和 local/prod 暴露策略。
  - [x] 保留 `/api/public/**` 匿名只读，其余后台路径默认拒绝。
  - [x] 为响应包装、异常、安全边界和配置编写测试。
- 执行记录：
  - 2026-07-22：开始执行；范围为统一响应/分页、错误处理、traceId、MyBatis-Plus 公共配置、审计字段和最小安全边界。
  - 2026-07-22：完成统一响应接入，并同步调整现有比赛接口测试和前端响应解析。
- 验证命令：

  ```bash
  npm run backend:test
  curl http://localhost:8080/actuator/health
  curl http://localhost:8080/v3/api-docs
  ```

- 完成标准：
  - 参数校验和业务异常返回统一格式。
  - 日志和响应能关联同一 traceId。
  - 分页大小有上限。
  - 后台路径在 T601 完成前默认拒绝。
  - `/actuator/health` 和 Swagger 在 local profile 可用。
  - 基础设施有 Controller/配置测试。
- 验证记录：
  - 2026-07-22：`mvn test` 共 19 个测试通过；参数/业务/未知异常、traceId、安全拒绝、分页上限、审计字段和 OpenAPI 测试通过。
  - 2026-07-22：`npm run frontend:build` 通过；临时端口启动验证 `/actuator/health` 为 `UP`、OpenAPI 为 `3.0.1`，验证后应用已停止。

### T005 前端依赖与测试基线

- 状态：`PARTIAL`
- 优先级：P1
- 依赖：T000
- 交付物：
  - Ant Design
  - TanStack Query
  - Vitest + Testing Library
  - 前端独立 `package-lock.json`
  - 测试初始化文件
- 执行步骤：
  - [x] 建立 React、Vite、TypeScript 和独立 `package-lock.json`。
  - [x] 配置开发服务器 `/api` 反向代理并通过生产构建。
  - [x] 安装 React Router 依赖。
  - [ ] 安装 Ant Design 和 TanStack Query。
  - [ ] 安装 Vitest、Testing Library、jsdom 和用户交互测试依赖。
  - [ ] 增加 `test`/`test:watch` 脚本和测试初始化文件。
  - [ ] 编写 App 冒烟测试，覆盖页面挂载和基础错误边界。
  - [ ] 使用干净依赖安装验证 lockfile 可重复性。
- 验证命令：

  ```bash
  cd frontend
  npm ci
  npm run build
  npm run test
  ```

- 恢复入口：T004 完成后安装前端依赖并先补测试基线，不继续堆页面业务。
- 执行记录：
  - 2026-07-22：完成 React/Vite/TypeScript、Router 依赖、API 代理和比赛列表构建；测试与状态管理基线未完成。
- 完成标准：
  - `npm run frontend:build` 通过。
  - 前端测试命令可执行并至少有一个 App 冒烟测试。
  - 干净克隆使用 `npm ci` 能还原相同依赖。
- 验证记录：
  - 2026-07-22：现有 React/Vite 比赛列表可通过 `npm run build`，测试框架尚未接入。

## 6. M1 Provider 基础与数据源验证

### T101 Provider 契约与配置

- 状态：`PARTIAL`
- 优先级：P0
- 依赖：T004
- 交付物：
  - `SportteryProvider`
  - `AsianOddsProvider`
  - Provider Properties
  - 内部请求/响应 Dto
- 执行步骤：
  - [x] 定义 `SportteryProvider` 和 `SportteryMatchDto`，与公开 `Vo` 隔离。
  - [x] 实现 `ChinaSportteryProvider`，只映射已验证的官方字段。
  - [x] 使用配置条件在真实体彩 Provider 与 Stub 之间切换。
  - [x] 用固定官方响应 fixture 编写适配器契约测试。
  - [ ] 定义 `AsianOddsProvider`、查询 Dto、比赛和盘口响应 Dto。
  - [ ] 新增 `SportteryProviderProperties` 与 `AsianOddsProviderProperties`。
  - [ ] 配置连接超时、读取超时、重试、额度阈值和可选 API Key。
  - [ ] 定义统一 Provider 错误分类，区分参数错误、限额、上游故障和解析失败。
  - [ ] 补齐空比赛池、异常响应、未知状态和让球缺失测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=MatchQueryServiceTest,ChinaSportteryProviderTest,StubSportteryProviderTest test
  npm run backend:test
  ```

- 恢复入口：等待 T004 `DONE` 后，先完成配置属性和超时，再定义亚盘契约。
- 执行记录：
  - 2026-07-22：为核对真实比赛先完成体彩查询契约与适配器；正式 Provider 基础任务等待 T004 后恢复。
- 完成标准：
  - Provider 返回明确 Dto，不向业务层暴露原始 JSON。
  - 连接、读取、重试和额度阈值可配置。
  - API Key 不出现在 `toString`、日志或异常中。

- 验证记录：
  - 2026-07-22：体彩契约、真实/Stub 适配器及 3 个相关测试通过，提交 `67352d3`。

### T102 Provider 与原始数据 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V1__init_provider_and_raw_data.sql`
  - Provider、原始响应和同步运行 Entity/Mapper
- 执行步骤：
  - [ ] 按 `technical-design.md` 字段定义编写 `V1__init_provider_and_raw_data.sql`。
  - [ ] 创建 Provider 配置、`raw_data_payloads`、`data_sync_runs` 表和约束。
  - [ ] 为状态、数据类型和解析结果定义业务枚举。
  - [ ] 创建 Entity、Mapper 与最小 Repository/Service 查询。
  - [ ] 增加 JSONB、SHA-256、请求键和幂等唯一约束测试。
  - [ ] 使用 Testcontainers 从空库执行 migration，并验证重复启动不重复执行。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MigrationTest test
  npm run backend:test
  ```

- 完成标准：
  - migration 可从空库执行。
  - 原始响应保存 JSONB 和 SHA-256。
  - 重复响应由唯一约束去重。
  - migration 集成测试通过。

### T103 Stub 双数据源

- 状态：`PARTIAL`
- 优先级：P0
- 依赖：T101
- 交付物：
  - 体彩比赛池、赛果和亚盘 fixtures
  - `StubSportteryProvider`
  - `StubAsianOddsProvider`
- 执行步骤：
  - [x] 实现稳定的 `StubSportteryProvider`，队名明确标注为演示数据。
  - [x] 编写 Stub 体彩比赛单元测试。
  - [ ] 增加体彩比赛池、正常赛果、延期、取消和修正 fixtures。
  - [ ] 增加亚盘正常、缺失、球队别名、时间冲突 fixtures。
  - [ ] 实现 `StubAsianOddsProvider` 和赛果 Stub。
  - [ ] 配置 test profile 强制使用 Stub，确保不会发起真实网络请求。
  - [ ] 验证同一输入重复运行输出完全一致。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Stub*Test test
  npm run backend:test
  ```

- 恢复入口：T101 完成后先补 fixtures，再实现亚盘 Stub。
- 执行记录：
  - 2026-07-22：完成最小体彩 Stub，尚未进入双数据源和异常场景范围。
- 完成标准：
  - 包含正常、缺失、别名、时间冲突、延期和取消样例。
  - dev/test profile 可明确切换到 Stub。
  - Stub 输出在重复运行时完全一致。

- 验证记录：
  - 2026-07-22：最小体彩 Stub 及稳定输出测试通过，提交 `67352d3`。

### T104 原始响应入库与同步运行服务

- 状态：`TODO`
- 优先级：P0
- 依赖：T102、T103
- 交付物：
  - `RawDataPayloadService`
  - `DataSyncRunService`
  - Provider 调用模板
- 执行步骤：
  - [ ] 定义同步运行创建、成功、部分成功和失败状态机。
  - [ ] 实现原始响应哈希、JSONB 保存、解析状态和错误信息追加。
  - [ ] 实现 Provider 调用模板，固定“运行 -> 请求 -> 原始入库 -> 解析 -> 完成”顺序。
  - [ ] 使用事务边界保证原始响应不会因领域解析失败而丢失。
  - [ ] 增加重复响应、单条解析失败、整批失败和恢复测试。
  - [ ] 记录请求耗时、记录数、重试次数和额度消耗。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*RawData*Test,*DataSyncRun*Test test
  npm run backend:test
  ```

- 完成标准：
  - 固定执行“创建运行 -> 请求 -> 原始入库 -> 解析 -> 完成运行”。
  - 解析失败保留原始响应和错误。
  - 单条失败不丢失整批成功结果。
  - 重复同步幂等测试通过。

### T105 Provider 重试、限额与契约测试

- 状态：`TODO`
- 优先级：P0
- 依赖：T101、T104
- 交付物：
  - WireMock 测试
  - 429/5xx/超时重试策略
  - 用量响应头解析和额度告警
- 执行步骤：
  - [ ] 为体彩与亚盘 HTTP 客户端统一接入可配置超时。
  - [ ] 定义仅对网络错误、429 和可恢复 5xx 生效的重试策略。
  - [ ] 解析 `Retry-After` 和供应商额度响应头。
  - [ ] 把每次尝试、最终状态和额度写入同步运行记录。
  - [ ] 使用 MockWebServer/WireMock 覆盖 400、401、429、500、超时和非法 JSON。
  - [ ] 检查日志、异常和测试快照没有 API Key、密码或 Cookie。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Provider*ContractTest,*Retry*Test test
  npm run backend:test
  ```

- 完成标准：
  - 4xx 参数错误不重试。
  - 429 尊重 `Retry-After`。
  - 重试和额度消耗进入同步记录。
  - 凭据不进入 WireMock 快照或测试报告。

### T106 体彩候选源两周验证

- 状态：`PARTIAL`
- 优先级：P0
- 依赖：T104
- 交付物：
  - 中国大陆节点访问记录
  - 字段字典和脱敏样例
  - 连续两周比赛池与赛果报告
- 执行步骤：
  - [x] 从官方竞彩足球页面确认真实比赛池请求 URL 和参数。
  - [x] 保存脱敏响应样本并建立首版字段字典。
  - [x] 核对 2026-07-22 页面与接口的比赛数量、编号、对阵、时间和让球。
  - [ ] 建立每日采集记录，连续 14 天记录请求时间、状态、数量和内容哈希。
  - [ ] 接入并核对官方赛果接口，覆盖正常完赛。
  - [ ] 收集延期、取消、改期和官方修正案例。
  - [ ] 在本地网络和中国大陆部署节点分别记录访问稳定性与 WAF 行为。
  - [ ] 核查网站条款、缓存、展示和商业使用授权边界。
  - [ ] 汇总字段完整率、赛果可获取率和生产风险结论。
- 验证方式：每天把观测结果追加到 `docs/data-sources.md` 的验证记录，不以一次成功代替连续验证。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Sporttery*ContractTest,*Sporttery*SyncTest test
  rg -n "14 天|体彩|赛果|延期|取消|修正|授权" docs/data-sources.md
  ```

- 恢复入口：T104 完成后建立自动采集与同步运行记录；授权调查可同步进行。
- 执行记录：
  - 2026-07-22：完成首次官方页面、请求 URL、真实响应和页面数据核对；连续观测尚未开始。
- 完成标准：
  - 比赛池字段完整率 100%。
  - 正常完赛场次赛果可获取率 100%。
  - 延期、取消和修正场景有记录。
  - 使用许可和生产访问风险有明确结论。

- 验证记录：
  - 2026-07-22：官方页面与比赛池接口首个真实样本核对完成，提交 `67352d3`。

### T107 亚盘候选源两周验证

- 状态：`TODO`
- 优先级：P0
- 依赖：T105
- 交付物：
  - The Odds API 实测接入
  - API-Football 或商业样例补测
  - 覆盖率、延迟、博彩公司和额度报告
- 执行步骤：
  - [ ] 注册 The Odds API 验证账号并仅通过环境变量配置 Key。
  - [ ] 拉取足球项目、联赛和 `spreads` 市场列表，记录实际请求成本。
  - [ ] 以每日体彩池为母集查询当天相关联赛，建立比赛映射样本。
  - [ ] 记录盘口值、主客赔率、博彩公司、更新时间和缺失原因。
  - [ ] 连续 14 天统计覆盖率、延迟、额度和接口错误率。
  - [ ] 对未覆盖比赛使用 API-Football 或一个商业样例源补测。
  - [ ] 核查历史数据、缓存、模型训练和产品展示授权。
  - [ ] 输出达到或未达到 90% 覆盖率的证据。
- 验证方式：每日记录母集数量、成功映射数、有效亚盘数和 credits；公式和样本写入 `data-sources.md`。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*AsianOdds*ContractTest,*AsianOdds*SyncTest test
  rg -n "14 天|亚盘|覆盖率|credits|额度|授权" docs/data-sources.md
  ```

- 完成标准：
  - 目标竞彩比赛亚盘覆盖率至少 90%。
  - 盘口、主客赔率、来源和时间戳完整。
  - 月度预计用量与预算明确。
  - 不满足门槛时有替代供应商结论。

### T108 数据源 Go / No-Go 决策

- 状态：`TODO`
- 优先级：P0
- 依赖：T106、T107、T205
- 交付物：
  - 更新后的 `data-sources.md`
  - 供应商选择与回退策略
  - 数据授权结论
- 执行步骤：
  - [ ] 汇总 T106/T107 的覆盖率、准确率、延迟、稳定性、额度和授权证据。
  - [ ] 汇总 T205 的真实比赛映射准确率和人工复核比例。
  - [ ] 对每个候选供应商给出 Go、Conditional Go 或 No-Go。
  - [ ] 明确主 Provider、回退 Provider、故障降级和预算。
  - [ ] 更新配置、部署要求、风险清单和 `data-sources.md` 最终结论。
  - [ ] 由产品负责人确认生产数据展示与使用许可后签字验收。
- 验证方式：逐条核对 `data-sources.md` Go / No-Go 门槛，任何缺失证据都不能标记 `DONE`。
- 验证命令：

  ```bash
  npm run backend:test
  rg -n "Go|Conditional Go|No-Go|覆盖率|映射准确率|SLA|授权" docs/data-sources.md
  ```

- 完成标准：
  - 体彩和亚盘生产 Provider 均明确。
  - 覆盖率、映射准确率、额度、SLA 和授权全部有证据。
  - 未通过时明确 `NO-GO`，不以 Stub 结果代替生产结论。

## 7. M2 比赛标准化与双源映射

### T201 比赛与映射 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V2__init_league_team_match_and_mapping.sql`
  - `V3__init_sporttery_and_asian_odds_snapshots.sql`
  - 对应 Entity/Mapper/Enum
- 执行步骤：
  - [ ] 从 `technical-design.md` 提取联赛、球队、比赛、来源映射和快照字段。
  - [ ] 编写 V2，创建联赛、球队、比赛和来源映射表。
  - [ ] 编写 V3，创建体彩池快照和亚盘快照追加表。
  - [ ] 增加业务唯一约束、状态检查、时间字段和必要索引。
  - [ ] 创建 Entity、Mapper 和供应商无关枚举。
  - [ ] 编写空库 migration、约束失败和重复执行测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MigrationTest,*ConstraintTest test
  npm run backend:test
  ```

- 完成标准：
  - 体彩比赛、联赛、球队、来源映射和快照表完整。
  - 唯一约束和检查约束生效。
  - 快照表只追加。
  - migration 集成测试通过。

### T202 体彩比赛池同步

- 状态：`TODO`
- 优先级：P0
- 依赖：T104、T201
- 交付物：
  - `SportteryPoolSyncService`
  - `SportteryPoolSyncJob`
  - 体彩快照写入
- 执行步骤：
  - [ ] 定义按竞彩 `businessDate` 同步的输入 Dto 和同步结果。
  - [ ] 从 `SportteryProvider` 读取比赛池并关联原始响应记录。
  - [ ] 按体彩比赛 ID、竞彩日期和来源幂等创建内部比赛。
  - [ ] 将 SP、让球和销售状态按采集时间追加为快照。
  - [ ] 实现手动触发 Service，再接入带开关的定时 Job。
  - [ ] 覆盖首次同步、重复同步、赔率变化、单场失败和空池测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SportteryPoolSync*Test test
  npm run backend:test
  ```

- 完成标准：
  - Stub 比赛池可幂等同步。
  - 同一体彩编号和日期不重复建比赛。
  - SP 和销售状态变化生成新快照，不覆盖旧快照。
  - 任务运行和异常可追溯。

### T203 联赛与球队标准化

- 状态：`TODO`
- 优先级：P0
- 依赖：T201
- 交付物：
  - 联赛标准化服务
  - 球队标准化服务
  - 已确认别名映射
- 执行步骤：
  - [ ] 定义名称规范化规则：空白、全半角、大小写、标点和常见后缀。
  - [ ] 建立联赛与球队的供应商外部 ID 映射优先规则。
  - [ ] 建立人工确认别名表，保存来源、确认人和时间。
  - [ ] 对未知名称只创建候选，不自动合并相似实体。
  - [ ] 为中文/英文别名、同名球队和符号差异编写参数化测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Normalization*Test test
  ```

- 完成标准：
  - 大小写、空白和常见符号标准化有测试。
  - 已确认外部 ID 优先于字符串匹配。
  - 不因名称相似直接合并不同球队。

### T204 双源比赛自动映射

- 状态：`TODO`
- 优先级：P0
- 依赖：T202、T203
- 交付物：
  - `MatchMappingService`
  - 置信度与解释字段
  - 待复核队列
- 执行步骤：
  - [ ] 定义映射输入：来源比赛 ID、标准联赛、主客队和开赛时间。
  - [ ] 先匹配已确认外部 ID，再计算名称与时间置信度。
  - [ ] 为主客队反转、联赛冲突和时间超差设置硬性拒绝规则。
  - [ ] 保存映射状态、分数、解释、方法和候选列表。
  - [ ] 高置信度自动确认，其他记录进入待复核队列。
  - [ ] 使用 Stub 和真实样本测试正确、缺失、反转、冲突和重复映射。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MatchMapping*Test test
  ```

- 完成标准：
  - 主客队、联赛和开赛时间共同参与映射。
  - 主客队反转和时间冲突进入待复核。
  - 外部比赛唯一映射约束生效。
  - Stub 自动映射准确率测试通过。

### T205 映射人工复核接口

- 状态：`TODO`
- 优先级：P0
- 依赖：T204、T004
- 交付物：
  - 映射列表、详情、确认和拒绝 Dto/Vo/API
  - 操作审计
- 执行步骤：
  - [ ] 定义待复核列表筛选 Dto、详情 Vo、确认 Dto 和拒绝 Dto。
  - [ ] 实现分页查询和候选差异展示所需聚合。
  - [ ] 实现确认、拒绝和重新打开的业务状态机。
  - [ ] 使用条件更新防止两名管理员并发确认冲突。
  - [ ] 每次操作追加审计记录，不覆盖历史决定。
  - [ ] 编写权限、参数、冲突、重复提交和审计测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MappingReview*Test test
  npm run backend:test
  ```

- 完成标准：
  - 低置信度记录可确认或拒绝。
  - 人工结果可被后续同步复用。
  - 确认冲突返回明确业务错误。
  - Controller 测试和 Service 测试通过。

### T206 亚盘快照同步

- 状态：`TODO`
- 优先级：P0
- 依赖：T104、T201、T204
- 交付物：
  - `AsianOddsSyncService`
  - `AsianOddsSyncJob`
  - 亚盘快照写入
- 执行步骤：
  - [ ] 从亚盘 Provider 拉取目标联赛和时间窗内的赛前盘口。
  - [ ] 关联原始响应、同步运行和已确认比赛映射。
  - [ ] 拒绝未映射、低置信度、滚球或字段不完整的盘口。
  - [ ] 按来源、博彩公司、比赛、盘口和采集时间追加快照。
  - [ ] 解析并累计额度，输出当日覆盖率。
  - [ ] 实现手动同步和带开关的定时 Job。
  - [ ] 覆盖重复快照、盘口变化、映射缺失和额度不足测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*AsianOddsSync*Test test
  ```

- 完成标准：
  - 只给已确认比赛写盘口。
  - 来源、博彩公司、盘口、赔率和时间戳完整。
  - 重复快照幂等。
  - 覆盖率和额度可统计。

## 8. M3 预测发布、锁定和快照

### T301 预测与快照 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V4__init_prediction_and_public_snapshot.sql`
  - Prediction/Snapshot Entity、Mapper 和枚举
- 执行步骤：
  - [ ] 定义预测、预测版本、公开快照和存储对象元数据字段。
  - [ ] 编写 V4 migration，包含概率范围、状态、版本和哈希约束。
  - [ ] 设计同一比赛/模型多版本唯一约束和当前版本查询索引。
  - [ ] 创建 Entity、Mapper、`PredictionStatus` 和快照状态枚举。
  - [ ] 编写空库迁移、非法概率、重复版本和状态约束测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Prediction*MigrationTest test
  ```

- 完成标准：
  - 概率、状态、版本和哈希约束完整。
  - 已发布版本可保留历史。
  - migration 集成测试通过。

### T302 模型结果导入

- 状态：`TODO`
- 优先级：P0
- 依赖：T202、T301
- 交付物：
  - `PredictionImportDto`
  - 导入校验和服务
  - 离线模型样例文件
- 执行步骤：
  - [ ] 定义包含比赛、模型版本、三项概率和生成时间的 `PredictionImportDto`。
  - [ ] 校验比赛存在、未开赛、概率在区间内且概率和满足精度要求。
  - [ ] 规范化小数精度，禁止以字符串或百分数字段混用。
  - [ ] 实现整批校验后写入，任何失败不产生半批数据。
  - [ ] 保存模型输入文件或批次哈希，支持重复导入幂等。
  - [ ] 编写边界概率、概率和错误、已开赛和重复批次测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*PredictionImport*Test test
  ```

- 完成标准：
  - 比赛、模型版本、概率边界和概率和校验完整。
  - 已开赛比赛拒绝导入。
  - 导入失败不生成半成品预测。

### T303 预测发布和版本化重发

- 状态：`TODO`
- 优先级：P0
- 依赖：T302
- 交付物：
  - `PredictionPublishService`
  - 发布 Dto/Vo/API
  - 内容规范化和 SHA-256
- 执行步骤：
  - [ ] 定义发布请求 Dto、发布结果 Vo 和管理员 API。
  - [ ] 根据比赛时间计算锁定时间并校验仍可发布。
  - [ ] 规范化预测内容，计算稳定 SHA-256。
  - [ ] 首次发布创建版本 1；重发创建新版本且保留旧版本。
  - [ ] 使用事务与唯一约束处理并发发布。
  - [ ] 追加发布审计并编写重复、重发、并发和过期测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*PredictionPublish*Test test
  ```

- 完成标准：
  - 发布写入时间、锁定时间、版本和哈希。
  - 重发生成新版本，旧版本不覆盖。
  - 并发发布结果一致且有测试。

### T304 预测锁定

- 状态：`TODO`
- 优先级：P0
- 依赖：T303
- 交付物：
  - `PredictionLockService`
  - `PredictionLockJob`
  - 条件更新 SQL
- 执行步骤：
  - [ ] 明确锁定时间边界和允许的状态迁移。
  - [ ] 实现基于数据库当前时间/条件更新的批量锁定。
  - [ ] 锁定后禁止修改比赛、模型版本、概率和核心预测内容。
  - [ ] 定时 Job 只处理到期未锁定记录，并支持重复执行。
  - [ ] 并发模拟发布、修改和锁定竞争，验证只有合法操作成功。
  - [ ] 记录锁定数量、失败数量和异常审计。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*PredictionLock*Test test
  ```

- 完成标准：
  - 锁定前后边界测试完整。
  - 锁定后核心字段不能修改。
  - 重复任务幂等。
  - 多线程并发修改测试通过。

### T305 公开预测快照

- 状态：`TODO`
- 优先级：P0
- 依赖：T303
- 交付物：
  - `SnapshotStorage`
  - `LocalSnapshotStorage`
  - `PredictionSnapshotService`
  - `SnapshotPublishJob`
- 执行步骤：
  - [ ] 定义公开快照 JSON schema 和字段排序/时间/小数规范化规则。
  - [ ] 定义 `SnapshotStorage` 接口与本地文件实现。
  - [ ] 从已发布预测生成规范化 JSON 和内容哈希。
  - [ ] 先写临时对象并校验哈希，再原子发布并更新数据库状态。
  - [ ] 同一事实重复生成必须得到相同字节和哈希。
  - [ ] 覆盖写入失败、数据库失败、重复发布和损坏文件测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Snapshot*Test test
  ```

- 完成标准：
  - 规范化 JSON 可复算。
  - 同一事实生成相同哈希。
  - 文件和数据库哈希一致。
  - 文件写入失败不会标记快照成功。

## 9. M4 赛果与自动结算

### T401 结算与审计 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V5__init_settlement_and_audit.sql`
  - `V6__add_core_indexes.sql`
  - Settlement/Audit Entity、Mapper 和枚举
- 执行步骤：
  - [ ] 定义比赛事实版本、结算、结算版本和追加式审计字段。
  - [ ] 编写 V5 migration，创建结算和审计表及业务约束。
  - [ ] 编写 V6，只增加有实际查询依据的核心索引。
  - [ ] 创建 Entity、Mapper、`SettlementStatus` 和审计事件枚举。
  - [ ] 验证结算唯一性、审计不可普通覆盖和索引查询计划。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Settlement*MigrationTest,*Audit*Test test
  ```

- 完成标准：
  - 结算唯一约束生效。
  - 审计表无普通覆盖或删除流程。
  - 待结算、历史和盘口查询索引有查询依据。

### T402 体彩赛果同步

- 状态：`TODO`
- 优先级：P0
- 依赖：T104、T202
- 交付物：
  - `MatchResultSyncService`
  - `MatchResultSyncJob`
  - 比赛状态流转
- 执行步骤：
  - [ ] 为体彩赛果定义明确 Provider Dto 和状态映射。
  - [ ] 通过原始响应与同步运行模板拉取指定日期范围赛果。
  - [ ] 只以最终官方比赛事实更新比赛状态和比分。
  - [ ] 对延期、取消、未完成和异常比分执行显式状态迁移。
  - [ ] 官方修正创建新事实版本并追加审计，不覆盖旧事实。
  - [ ] 实现定时补数 Job 和正常/延期/取消/修正测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MatchResultSync*Test test
  ```

- 完成标准：
  - Stub 正常、延期、取消和修正赛果可处理。
  - 非法状态回退被拒绝或进入异常。
  - 赛果修正保留审计。

### T403 市场结算器

- 状态：`TODO`
- 优先级：P0
- 依赖：T401
- 交付物：
  - 胜平负结算器
  - 体彩让球胜平负结算器
  - 参数化测试矩阵
- 执行步骤：
  - [ ] 固定胜平负和体彩让球胜平负输入、输出与异常类型。
  - [ ] 实现无数据库依赖的胜平负纯函数结算器。
  - [ ] 实现正负整数让球的三结果纯函数结算器。
  - [ ] 建立主胜、平、客胜以及让球后胜平负的参数化矩阵。
  - [ ] 覆盖缺失比分、非整数体彩让球和未知市场错误。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SettlementCalculatorTest test
  ```

- 完成标准：
  - 胜平负和体彩正负整数让球矩阵覆盖。
  - 计算器是纯函数，不读 Controller 或数据库。
  - 异常市场输入返回明确错误。

### T404 自动结算任务

- 状态：`TODO`
- 优先级：P0
- 依赖：T304、T402、T403
- 交付物：
  - `SettlementService`
  - `SettlementJob`
  - 结算审计
- 执行步骤：
  - [ ] 查询已锁定、比赛事实已确认且尚未结算的预测版本。
  - [ ] 按市场调用纯函数结算器并保存输入事实版本和规则版本。
  - [ ] 使用唯一约束和事务保证重复运行不重复结算。
  - [ ] 单场事务失败只标记该场异常，继续处理其他比赛。
  - [ ] 定时 Job 输出批次、成功、失败和待人工处理数量。
  - [ ] 覆盖批量、重复、部分失败、未锁定和未完赛测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SettlementServiceTest,*SettlementJobTest test
  ```

- 完成标准：
  - 只结算已锁定且已确认完赛的预测。
  - 重复执行不重复结算。
  - 单场失败不阻塞整批。
  - 人工不能直接写结算结果。

### T405 赛果修正与结算重算

- 状态：`TODO`
- 优先级：P1
- 依赖：T404
- 交付物：
  - 事实版本和重算流程
  - 旧结算保留策略
- 执行步骤：
  - [ ] 检测当前结算引用的事实版本与最新官方事实版本差异。
  - [ ] 将旧结算标记为被替代，但保留内容和审计。
  - [ ] 使用相同规则版本或明确升级规则生成新结算版本。
  - [ ] 保存重算原因、操作者/任务、前后事实和前后结算关系。
  - [ ] 在公开查询中标识修正与重算，不静默替换。
  - [ ] 覆盖比分修正、状态修正、重复重算和并发测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SettlementRecalculation*Test test
  ```

- 完成标准：
  - 官方修正不会静默覆盖历史。
  - 新结算可追溯到输入事实和规则版本。
  - 前端能标识“赛果修正后重算”。

## 10. M5 公共 API 与前端

### T501 公共查询 API

- 状态：`PARTIAL`
- 优先级：P0
- 依赖：T202、T303、T404、T004
- 交付物：
  - 首页、比赛列表、详情、历史、统计 Dto/Vo/API
- 执行步骤：
  - [x] 建立显式 `MatchSummaryVo` 和按竞彩日期查询的公共比赛列表接口。
  - [x] 编写比赛列表 Controller 测试和 Service 映射测试。
  - [ ] 在 T004 完成后统一接入 `ApiResponse`、错误码和 traceId。
  - [ ] 将列表从实时 Provider 查询切换为 T202 持久化比赛池查询。
  - [ ] 增加分页、日期、联赛、状态筛选和排序白名单。
  - [ ] 定义并实现比赛详情 Vo/API，明确区分体彩让球与亚洲盘。
  - [ ] 定义并实现历史全量记录 Vo/API，不隐藏未命中记录。
  - [ ] 定义并实现统计 Vo/API，明确样本量和指标口径。
  - [ ] 为每个 API 编写参数、空结果、权限和集成测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*ControllerTest,*QueryServiceTest test
  npm run backend:test
  ```

- 恢复入口：依赖 T202/T303/T404 完成后，先改造比赛列表为数据库查询，再实现详情。
- 执行记录：
  - 2026-07-22：提前完成实时比赛列表原型，用于验证体彩 Provider；正式公共查询依赖尚未满足。
- 完成标准：
  - 分页、筛选和排序白名单生效。
  - 所有已知响应结构显式建模。
  - 历史接口返回全量命中和未命中记录。
  - Controller 与集成测试通过。

- 验证记录：
  - 2026-07-22：实时体彩比赛列表 API、显式 Vo 和 Controller 测试完成，提交 `21585e8`、`67352d3`。

### T502 前端路由、请求和布局

- 状态：`TODO`
- 优先级：P0
- 依赖：T005
- 交付物：
  - Router
  - QueryClient
  - HTTP Service
  - Public/Admin Layout
- 执行步骤：
  - [ ] 安装并初始化 React Router、TanStack Query 和 Ant Design。
  - [ ] 创建公共布局、管理布局、404 和全局错误边界。
  - [ ] 创建统一 HTTP Client，处理基础 URL、JSON、超时和 traceId。
  - [ ] 将服务端请求迁移到 Query hooks，统一 loading/error/stale 策略。
  - [ ] 定义路由懒加载和公共/后台权限边界。
  - [ ] 编写布局、路由、HTTP 错误和 QueryClient 冒烟测试。
- 验证命令：

  ```bash
  cd frontend
  npm run test
  npm run build
  ```

- 完成标准：
  - 错误、traceId 和登录失效统一处理。
  - 服务端状态由 TanStack Query 管理。
  - 路由冒烟测试通过。

### T503 比赛列表与详情前后端

- 状态：`PARTIAL`
- 优先级：P0
- 依赖：T501、T502
- 交付物：
  - 比赛列表页
  - 比赛详情页
  - 对应 API Service 和类型
- 执行步骤：
  - [x] 完成比赛列表 Demo、日期选择、比赛卡片和数据源标识。
  - [x] 处理列表 loading、empty 和 error 状态。
  - [x] 对体彩让球缺失使用可空字段和明确文案。
  - [ ] 抽取 Match API Service、Type 和 TanStack Query hooks。
  - [ ] 增加联赛、状态筛选和稳定排序，筛选同步到 URL。
  - [ ] 实现比赛详情路由和基础信息区。
  - [ ] 分区展示体彩 SP、体彩让球和亚盘快照，禁止混用标签。
  - [ ] 增加 stale/最后更新时间、刷新和上游故障提示。
  - [ ] 编写列表交互、空态、错误、筛选、跳转和详情测试。
- 验证命令：

  ```bash
  cd frontend
  npm run test
  npm run build
  ```

- 恢复入口：T501/T502 `DONE` 后先抽 API Service 和 Query hook，再添加详情页。
- 执行记录：
  - 2026-07-22：完成比赛列表 UI 原型及真实数据源标识；路由、Query、筛选和详情未开始。
- 完成标准：
  - 体彩让球与亚洲盘明确区分。
  - loading、empty、error、stale 状态完整。
  - 列表筛选和详情跳转测试通过。

- 验证记录：
  - 2026-07-22：比赛列表 Demo 与真实数据源标识完成，`npm run build` 通过，提交 `21585e8`、`67352d3`。

### T504 历史与统计前后端

- 状态：`TODO`
- 优先级：P0
- 依赖：T501、T502
- 交付物：
  - 历史记录页
  - 统计分析页
  - Brier Score、Log Loss 和条件化 ROI 展示
- 执行步骤：
  - [ ] 固定历史记录和统计指标 API 类型及口径说明。
  - [ ] 实现历史列表 Query hook、分页和筛选 URL 状态。
  - [ ] 展示预测版本、发布时间、赛果、命中/未命中和修正标识。
  - [ ] 实现统计页时间范围、联赛和模型版本筛选。
  - [ ] 展示样本量、Brier Score、Log Loss；仅在赔率和下注规则完整时展示 ROI。
  - [ ] 编写未命中保留、筛选、空数据、指标缺失和移动端测试。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  ```

- 完成标准：
  - 未命中记录不被隐藏。
  - ROI 未满足赔率和策略口径时不展示伪数值。
  - 按联赛、模型版本和时间范围筛选正确。

### T505 首页汇总

- 状态：`TODO`
- 优先级：P1
- 依赖：T503、T504
- 交付物：
  - 首页指标卡片
  - 风险提示和历史入口
- 执行步骤：
  - [ ] 定义首页聚合 Vo，所有指标可追溯到事实查询。
  - [ ] 实现今日比赛、已发布、待结算和近期表现聚合。
  - [ ] 展示数据最后更新时间、延迟、样本量和风险提示。
  - [ ] 增加比赛、历史、统计入口和响应式布局。
  - [ ] 对未达到数据口径的指标显示“暂无”，不填充伪数值。
  - [ ] 编写聚合 Service 和前端窄屏/空态测试。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  ```

- 完成标准：
  - 指标可从事实数据重建。
  - 数据延迟和最后更新时间可见。
  - 移动端窄屏可阅读。

## 11. M6 后台、可观测性与上线

### T601 管理员鉴权

- 状态：`TODO`
- 优先级：P0
- 依赖：T004
- 交付物：
  - Spring Security 配置
  - JWT 登录
  - 管理员角色
- 执行步骤：
  - [ ] 明确管理员账号来源、密码哈希、Token 有效期和退出策略。
  - [ ] 创建管理员表/配置和必要 migration，不开放普通用户注册。
  - [ ] 实现登录 Dto/Vo、认证 Service、JWT 签发与校验。
  - [ ] 配置 `/api/public/**` 匿名只读、`/api/admin/**` 必须管理员权限。
  - [ ] 对登录失败和权限拒绝使用统一错误响应并记录安全审计。
  - [ ] 编写有效、过期、伪造 Token 及公共/后台边界测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Security*Test,*Auth*Test test
  npm run backend:test
  ```

- 完成标准：
  - 公共 API 匿名只读。
  - 后台 API 未授权返回 401/403。
  - 不开放普通用户注册。
  - 安全配置测试通过。

### T602 后台任务与映射复核页面

- 状态：`TODO`
- 优先级：P0
- 依赖：T205、T502、T601
- 交付物：
  - 同步运行页
  - 映射复核页
  - 预测锁定和待结算状态页
- 执行步骤：
  - [ ] 实现后台同步运行列表、详情、错误和额度 API。
  - [ ] 实现映射待复核列表、候选对比、确认和拒绝交互。
  - [ ] 实现预测状态、锁定状态和待结算异常列表。
  - [ ] 关键操作增加二次确认、权限校验和追加式审计。
  - [ ] 原始响应只展示脱敏片段，不展示凭据、Cookie 或授权头。
  - [ ] 编写权限、交互、冲突、错误和审计测试。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  ```

- 完成标准：
  - 任务、额度、错误和待处理数量可见。
  - 关键操作要求鉴权并写审计。
  - 不展示未脱敏的原始凭据。

### T603 指标、日志与告警

- 状态：`TODO`
- 优先级：P0
- 依赖：T104、T304、T404
- 交付物：
  - Actuator/Micrometer 指标
  - 结构化关键日志
  - 数据源、锁定、结算和快照告警
- 执行步骤：
  - [ ] 定义 API、Provider、同步、映射、锁定、结算和快照指标名称。
  - [ ] 输出结构化日志并统一 traceId、jobName、providerCode 和业务 ID。
  - [ ] 对密码、API Key、Authorization、Cookie 和原始响应敏感字段脱敏。
  - [ ] 配置健康检查、数据库/Redis 状态和只暴露必要 Actuator 端点。
  - [ ] 为覆盖率、额度、同步延迟、异常堆积和任务失败定义告警阈值。
  - [ ] 使用故障注入验证日志、指标和告警实际触发。
- 验证命令：

  ```bash
  npm run backend:test
  curl http://localhost:8080/actuator/health
  curl http://localhost:8080/actuator/metrics
  ```

- 完成标准：
  - 关键日志包含 traceId、jobName、providerCode 和业务 ID。
  - API Key、密码、Authorization 和 Cookie 被脱敏。
  - 覆盖率不足、额度耗尽和任务延迟可告警。

### T604 Docker 与 Nginx 部署

- 状态：`TODO`
- 优先级：P0
- 依赖：T501、T601、T603
- 交付物：
  - 后端 Dockerfile
  - 前端 Dockerfile
  - Nginx 配置
  - 生产 Compose 或部署模板
- 执行步骤：
  - [ ] 编写多阶段后端 Dockerfile，以非 root 用户运行 JAR。
  - [ ] 编写前端构建与 Nginx 静态资源镜像。
  - [ ] 配置 `/api` 反向代理、压缩、缓存、安全头和 SPA 回退。
  - [ ] 编写生产部署模板，只引用环境变量/Secret，不包含开发凭据。
  - [ ] 明确 Flyway 只能由一个受控实例执行，其他实例关闭迁移竞争。
  - [ ] 从空环境启动 PostgreSQL/Redis/后端/前端并执行冒烟测试。
  - [ ] 编写升级、回滚、日志查看和备份恢复操作说明。
- 验证命令：

  ```bash
  docker build -t jingcai-compass-backend ./backend
  docker build -t jingcai-compass-frontend ./frontend
  docker compose config
  docker compose up -d
  ```

- 完成标准：
  - 后端以非 root 用户运行。
  - 前端静态资源和 API 反向代理正常。
  - 迁移只由受控实例执行。
  - 从空环境按文档部署成功。

### T605 MVP 验收与连续运行

- 状态：`TODO`
- 优先级：P0
- 依赖：T108、T305、T404、T505、T602、T604
- 交付物：
  - 验收报告
  - 连续运行报告
  - 备份恢复记录
  - 已知风险清单
- 执行步骤：
  - [ ] 按 `requirements-mvp.md` 逐条执行功能验收并保存结果。
  - [ ] 验证数据源达到 T108 Go 标准，未达标则停止生产发布。
  - [ ] 演练预测导入、发布、锁定、快照、赛果、结算和历史全链路。
  - [ ] 连续运行约定周期，统计同步成功率、延迟、覆盖率和异常积压。
  - [ ] 执行数据库备份、清空测试环境、恢复和一致性核对。
  - [ ] 完成风险提示、免责声明、隐私说明和数据授权归档。
  - [ ] 记录已知问题、负责人、修复计划和最终上线决定。
- 验证方式：验收报告中的每个结论必须链接到测试、日志、监控或人工签字证据。
- 完成标准：
  - 数据源达到 Go 标准。
  - 发布、锁定、快照、结算和历史闭环通过。
  - 连续运行期间无静默丢数。
  - 数据库备份恢复演练通过。
  - 风险提示、免责声明和隐私说明存在。

## 12. 任务记录模板

新增任务或开始尚无记录的任务时，复制以下结构。步骤必须是可按顺序执行、可单独判断完成的动作：

```markdown
### Txxx 任务名称

- 状态：`TODO`
- 优先级：P0
- 依赖：Txxx
- 交付物：
  - 文件、类、migration 或页面
- 执行步骤：
  - [ ] 第一步
  - [ ] 第二步
- 验证命令：
  - `实际可运行的命令`
- 完成标准：
  - 可观察、可测试的结果
- 恢复入口：任务为 `PARTIAL` 时必填，说明下次第一步。
- 阻塞说明：任务为 `BLOCKED` 时必填，说明证据和解除条件。
- 执行记录：
  - YYYY-MM-DD：开始，范围……
- 验证记录：
  - YYYY-MM-DD：命令……，结果……，提交 `<commit>`。
```

状态更新检查：

```text
开始前：依赖 DONE -> 本任务 IN_PROGRESS -> 更新当前活动任务
开发中：逐项勾选 -> 范围变化先改文档
结束时：运行验证 -> 写验证记录 -> DONE/PARTIAL/BLOCKED
提交前：更新里程碑 -> 更新下一任务 -> 代码与文档一起提交
```

## 13. 必写测试清单

后端：

- Flyway 空库迁移。
- Provider 正常、空、异常、429、超时。
- 原始数据入库和幂等。
- 比赛映射冲突和人工确认。
- 预测概率校验、并发发布和锁定。
- 快照确定性哈希。
- 所有市场结算矩阵。
- 赛果修正和结算重算。
- 公开历史包含未命中记录。
- Spring Security 公共/后台边界。

前端：

- 路由和布局。
- 列表筛选和分页。
- 详情不同市场字段。
- loading、empty、error、stale。
- 历史全量展示。
- 后台映射确认。

## 14. 推荐的下一步

当前没有 `IN_PROGRESS` 任务。下一次开发按以下步骤恢复 `T005 前端依赖与测试基线`：

1. 将文档顶部“当前活动任务”改为 T005，并把 T005 从 `PARTIAL` 改为 `IN_PROGRESS`。
2. 安装 Ant Design、TanStack Query、Vitest、Testing Library 和 jsdom。
3. 增加 `test`/`test:watch` 脚本与测试初始化文件。
4. 编写 App 冒烟测试和基础错误边界测试。
5. 使用 `npm ci` 验证前端 lockfile 可重复安装。
6. 运行 T005 验证命令及通用检查并回写状态。

随后严格按以下顺序补齐底座：

```text
T005 -> 恢复 T101
```

在 T005 `DONE` 前，不继续扩展当前实时比赛列表的业务功能；已经存在的 Provider 和页面只作为经过验证的纵向原型保留。

## 15. 变更记录

| 日期 | 任务/提交 | 状态变化 | 验证或说明 |
| --- | --- | --- | --- |
| 2026-07-22 | T000 | `DONE` | 建立需求、数据源、技术设计、实施指南和执行看板 |
| 2026-07-22 | T001 / `9563693` | `DONE` | 云端开发连接、配置约定和启动验证完成 |
| 2026-07-22 | T501、T503 / `21585e8` | `TODO -> PARTIAL` | 首个比赛列表 Demo，后端测试和前端构建通过 |
| 2026-07-22 | T101、T103、T106、T501、T503 / `67352d3` | `PARTIAL` | 重构查询分层、接入真实体彩比赛池并校准文档；后端 5 个测试、前端构建通过 |
| 2026-07-22 | T000 / 文档 v0.2 | `DONE` | 将本文件升级为逐步执行手册；当前无活动任务，下一任务 T002 |
| 2026-07-22 | T002 | `PARTIAL -> DONE` | 完成生产配置、类型安全配置、HTTP 超时、Actuator/OpenAPI 和 10 个后端测试；当时下一任务为 T003（后决定跳过） |
| 2026-07-22 | T003 | `TODO -> SKIPPED` | 项目负责人决定不安装本地 Docker；保留测试禁连共享云数据库约束，下一任务 T004 |
| 2026-07-22 | T004 | `TODO -> DONE` | 完成统一响应、异常、traceId、分页、审计、安全和 OpenAPI；后端 19 个测试、前端构建及端点启动验证通过 |
