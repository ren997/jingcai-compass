# 竞彩罗盘开发执行手册与任务看板

## 0. 文档状态

- 文档版本：v0.5
- 最后更新：2026-07-30
- 作用：本项目唯一的开发顺序、任务状态和验收记录入口
- 当前活动任务：`T208 比赛映射复核时效修正`
- 下一任务：`完成 T208 时效修正的本地与 PostgreSQL 验证`
- 最近完成增量：`T208 联赛与球队标准化复核闭环`

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
5. 开始写代码。同一时间只能有一个产品开发任务处于 `IN_PROGRESS`；连续数据观测可使用 `MONITORING`，不占用开发中的 WIP 名额。

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

### 2.4 新会话恢复与 GitHub Actions 交付

聊天上下文不作为项目事实来源。新会话进入仓库后，必须先读取根目录 `AGENTS.md`、本文件顶部状态和当前任务完整执行记录，再执行以下只读检查：

```bash
git status --short --branch
git log -5 --oneline --decorate
git remote -v
gh --version
gh auth status
```

- 以当前工作区、Git 提交、任务看板、PR 和 CI 记录恢复“已完成、当前任务、下一步和阻塞”；不要凭旧聊天内容推断。
- 工作区有未提交改动时先确认其归属并保留，不得用 reset、checkout 或覆盖文件的方式清理。
- 当前已经位于 `codex/*` 任务分支时继续该分支，不重复建分支；已有 PR 时先用 `gh pr view` 恢复 PR 和检查状态。
- 本文件与 Git 不一致时先核对提交和 PR，再修正文档；不得为让状态看起来一致而丢弃代码。
- `gh` 不存在时先安装 GitHub CLI；`gh auth status` 已成功时复用现有授权，不重复要求登录。只有状态失败时才运行 `gh auth login`，且不得把 Token 写入仓库、配置样例、命令日志或任务记录。

按验收手段决定交付方式：

- 任务依赖 Testcontainers、PostgreSQL 专有约束/并发、托管 Runner 或任务明确要求 `-Pintegration verify` 时，必须使用独立 `codex/<task>-<topic>` 分支、Draft PR 和 GitHub Actions。
- 全部完成标准可由本机测试满足，且项目负责人未要求发布时，可以只在本地开发，不自动推送或创建 PR。
- 本机没有 Docker 时只运行普通测试；禁止把共享开发库或云数据库作为 Testcontainers/CI 的替代品。

需要 GitHub Actions 的新任务，在工作区干净且本地 `master` 没有未发布提交时按以下顺序开始：

```bash
git switch master
git pull --ff-only origin master
git switch -c codex/tNNN-short-topic
```

如果本地 `master` 已领先、落后或分叉，先核对提交归属；不得强制重置。实现完成后执行任务专项测试、普通测试和 `git diff --check`，只显式暂存本任务文件，然后提交、推送并创建 Draft PR：

```bash
git add <本任务文件>
git commit -m "<type>(<module>): <中文主题>"
git push -u origin codex/tNNN-short-topic
gh pr create --draft --base master --head codex/tNNN-short-topic --fill
```

`.github/workflows/backend-integration.yml` 会在相关 PR、`master` push 或手动触发时使用 Java 21、Maven、Docker/Testcontainers 和 PostgreSQL 16 执行：

```bash
mvn -B -ntp -f backend/pom.xml -Pintegration verify
```

查看、手动触发和排查 Actions：

```bash
gh pr checks <PR编号> --watch --interval 10
gh workflow run backend-integration.yml --ref codex/tNNN-short-topic
gh run view <运行编号> --log-failed
gh run download <运行编号> -n backend-test-reports
```

CI 失败时在同一任务分支修复、提交并推送，由 PR 自动重新验证；不得改用共享数据库绕过失败。Actions 被禁用、无 Runner 配额或外部设施持续不可用时，记录证据和解除条件，任务保持 `PARTIAL` 或改为 `BLOCKED`。

首次实现 CI 成功后，在任务执行记录中写入：

- 实现提交 SHA、PR 链接和 Actions 运行链接/编号。
- Java、Maven、PostgreSQL 和固定容器镜像版本。
- 普通测试、全部 PostgreSQL IT 及本任务新增 IT 数量。
- 最新 Flyway 版本，以及该任务实际验证的约束、事务、幂等或并发行为。

只有完成标准全部满足且上述证据齐全后才能把任务改为 `DONE`。提交最终任务看板会再次触发 CI，必须等待该检查成功后再合并：

```bash
git add docs/dev-tasks.md
git commit -m "docs(plan): 完成TNNN任务名称"
git push
gh pr checks <PR编号> --watch --interval 10
gh pr ready <PR编号>
gh pr merge <PR编号> --merge
git switch master
git pull --ff-only origin master
git status --short --branch
```

最终状态应为：PR 已合并、远端 `master` 包含任务提交、本地 `master` 与 `origin/master` 一致、工作区干净，任务看板指向正确的下一任务。

### 2.5 状态定义

- `TODO`：未开始。
- `IN_PROGRESS`：当前正在执行；全项目只能有一个。
- `MONITORING`：按固定周期持续采集外部证据；可以与一个 `IN_PROGRESS` 产品开发任务并存。
- `PARTIAL`：已有可验证增量，但任务未达到全部完成标准，当前没有继续执行。
- `BLOCKED`：有明确外部阻塞，并在任务下说明原因。
- `SKIPPED`：项目负责人明确决定不执行；必须记录原因、已接受风险和后续替代验证方式。
- `DONE`：代码、测试、文档和完成标准均已满足。

### 2.6 文档职责

- `requirements-mvp.md`：定义做什么、不做什么和业务验收口径。
- `technical-design.md`：定义技术栈、架构边界、数据模型和不可违反的规则。
- `implementation-guide.md`：提供较长的实现示例、建表建议和编码说明。
- `data-sources.md`：记录供应商证据、覆盖率、授权与 Go / No-Go 结论。
- `dev-tasks.md`：决定现在做哪一项、按什么顺序做、做到什么程度和如何验证。

## 3. 全量执行顺序

默认严格按下列阶段推进；括号内任务可在其依赖完成后穿插，但不能绕过 `DONE` 依赖：

```text
阶段 A 工程底座
T000 -> T001 -> T002 -> T003(SKIPPED) -> T004 -> T005 -> T006

阶段 B Provider 与原始数据
T101 -> T102 -> T103 -> T104 -> T105
                         -> T106（连续观测，启动后为 MONITORING）
                         -> T107（连续观测，启动后为 MONITORING）

阶段 C 比赛标准化与双源映射
T201 -> T202 -> T203 -> T204 -> T205 -> T206 -> T207 -> T208

阶段 D 预测发布闭环
T301 -> T302 -> T601 -> T303 -> T304
                            -> T305

阶段 E 赛果与结算
T301 + T601 -> T401 -> T402
T000 -> T403（规则已固定，推荐在 T402 前完成）
T304 + T402 + T403 -> T404 -> T405

阶段 F 公共产品
T501 + T502 -> T503
T303 + T305 + T503 -> T506
T404 + T405 -> T507
T502 + T507 -> T504
T503 + T504 + T506 -> T505

阶段 G 上线
T205 + T502 + T601 -> T602
T104 + T105 -> T603
T304 + T404 + T502 + T601 -> T606
T304 + T305 + T404 + T603 -> T607
T108 + T505 + T601 + T603 + T607 -> T604
T305 + T405 + T505 + T602 + T604 + T606 -> T605
```

说明：

- T003 因不安装本地 Docker 保持 `SKIPPED`；T006 使用 CI 或远程临时 PostgreSQL 补回空库迁移和完整上下文验证，不要求本地安装 Docker。
- T106/T107 是连续观测轨，不阻塞使用 Stub 的领域开发；启动每日采集后改为 `MONITORING`。启动前必须记录负责人、开始日期、所需凭据/部署节点、预算上限和第 14 天的决策日期；T108 仍是生产数据源和上线的硬闸门。
- T601 提前到 T303 之前，因为预测发布交付管理员 API；公共产品按比赛、预测、历史统计三个纵向增量推进，不再由一个大任务统一阻塞。
- 此前为验证产品和数据源提前完成了比赛列表纵向切片，因此 T106、T501、T502、T503 当前为 `PARTIAL`。

## 4. 当前进度

| 里程碑 | 状态 | 说明 |
| --- | --- | --- |
| M0 工程基线 | `DONE` | T000～T006 已完成；GitHub Actions 已通过 PostgreSQL 16 空库迁移和完整数据库上下文验证 |
| M1 Provider 基础 | `PARTIAL` | T101～T105 已完成；T106/T107 连续观测和授权结论尚未完成 |
| M2 标准化与映射 | `PARTIAL` | T201～T208 的联赛、球队独立复核已完成；比赛映射复核时效修正进行中，未确认时不得由单场比赛映射反推别名 |
| M3 预测发布闭环 | `DONE` | T301～T305、T601 已完成；预测导入、发布、锁定和确定性公开快照闭环已通过验证 |
| M4 赛果与结算 | `DONE` | T401～T405 已完成；公开修正标识由 T507/T504 基于结算版本链交付 |
| M5 公共 API 与前端 | `DONE` | T501、T502、T503、T504、T505、T506、T507 已完成；公开比赛、预测、历史、统计与首页闭环均由持久化事实查询支撑 |
| M6 后台、稳定性与上线 | `PARTIAL` | T601 管理员鉴权、T602 后台同步/映射复核、T603 基础可观测性与 T606 预测/结算运营状态已完成；业务指标、部署及真实数据源上线条件尚未完成 |

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

- 状态：`DONE`
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
  - [x] 安装 Ant Design 和 TanStack Query。
  - [x] 安装 Vitest、Testing Library、jsdom 和用户交互测试依赖。
  - [x] 增加 `test`/`test:watch` 脚本和测试初始化文件。
  - [x] 编写 App 冒烟测试，覆盖页面挂载和基础错误边界。
  - [x] 使用干净依赖安装验证 lockfile 可重复性。
- 验证命令：

  ```bash
  cd frontend
  npm ci
  npm run build
  npm run test
  ```

- 恢复入口：已在 T004 完成后恢复并补齐前端测试基线。
- 执行记录：
  - 2026-07-22：完成 React/Vite/TypeScript、Router 依赖、API 代理和比赛列表构建；测试与状态管理基线未完成。
  - 2026-07-22：恢复执行；范围为 Ant Design、TanStack Query、Vitest/Testing Library、测试脚本、错误边界与 lockfile 验证。
  - 2026-07-22：完成 QueryClient/ConfigProvider 接入，将比赛请求迁移到 TanStack Query，并增加应用错误边界。
- 完成标准：
  - `npm run frontend:build` 通过。
  - 前端测试命令可执行并至少有一个 App 冒烟测试。
  - 干净克隆使用 `npm ci` 能还原相同依赖。
- 验证记录：
  - 2026-07-22：现有 React/Vite 比赛列表可通过 `npm run build`，测试框架尚未接入。
  - 2026-07-22：`npm ci` 成功还原 225 个包且审计 0 漏洞；Vitest 3 个测试、前端生产构建和后端 19 个回归测试通过。

### T006 PostgreSQL 空库迁移集成验证

- 状态：`DONE`
- 优先级：P0
- 依赖：T002；承接 T003 跳过后的 PostgreSQL 集成验证缺口
- 交付物：
  - Maven integration profile 与 Testcontainers PostgreSQL 测试依赖
  - `application-integration.yml`
  - 完整 Flyway 空库迁移和应用上下文集成测试
  - CI 或远程临时 PostgreSQL 执行入口与验证记录
- 执行步骤：
  - [x] 选择不要求开发机安装 Docker 的执行载体，优先使用托管 CI Runner；不可用时使用一次性远程 Docker/PostgreSQL。
  - [x] 将 Testcontainers PostgreSQL 依赖和集成测试放入独立 Maven profile，普通本地单测不启动容器。
  - [x] 创建 integration profile，强制使用 Stub Provider、关闭定时任务和真实 Redis 外联。
  - [x] 增加保护性断言，拒绝共享云数据库、开发数据库或非容器 JDBC URL。
  - [x] 从 PostgreSQL 空库执行 V1 到当前最新 migration，并恢复完整 Spring 应用上下文测试。
  - [x] 覆盖 JSONB、唯一/检查约束、时区精度、事务回滚和重复启动不重复迁移。
  - [x] 在 CI 或远程临时环境运行并保存命令、数据库版本和测试结果。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Pintegration verify
  npm run backend:test
  git diff --check
  ```

- 完成标准：
  - 开发机不安装 Docker 仍可运行普通单测。
  - CI 或远程临时 PostgreSQL 能从空库执行全部 migration 并启动完整应用上下文。
  - 集成测试明确拒绝共享云端和开发数据库。
  - 后续每个 migration 都进入同一条空库验证链路。
- 恢复入口：先确认可用的托管 CI Runner 或远程临时 PostgreSQL，再选择具体实现，不重新启用共享云数据库测试。
- 执行记录：
  - 2026-07-25：开始执行；使用 GitHub Actions 托管 Runner、Java 21、Maven Failsafe 和 Testcontainers PostgreSQL 16；保留本地快速测试无容器，预计验证 `mvn -f backend/pom.xml -Pintegration verify`、`npm run backend:test` 与 `git diff --check`。
  - 2026-07-25：[GitHub Actions #30163071243](https://github.com/ren997/jingcai-compass/actions/runs/30163071243) 在提交 `69f2e6823637116bc7e69b3244479eef293ba0a4` 上通过；Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）。
  - 2026-07-25：`mvn -f backend/pom.xml -Pintegration verify` 成功；129 个单元测试与 3 个 `PostgresApplicationIT` 全部通过。Flyway 从空库成功执行 V1～V6，再次 migrate 保持 v6 且无新增执行；真实 PostgreSQL 的 JSONB、唯一/CHECK 约束、`TIMESTAMPTZ`、事务回滚和容器 JDBC 保护断言通过。

## 6. M1 Provider 基础与数据源验证

### T101 Provider 契约与配置

- 状态：`DONE`
- 优先级：P0
- 依赖：T004
- 交付物：
  - `SportteryProvider`
  - `AsianOddsProvider`
  - Provider Properties
  - 内部请求/响应 Dto
  - stableflow 风格角色分包（`dto`/`vo`/`service`/`client`/`enums`）
- 执行步骤：
  - [x] 定义 `SportteryProvider` 和 `SportteryMatchDto`，与公开 `Vo` 隔离。
  - [x] 实现 `ChinaSportteryProvider`，只映射已验证的官方字段。
  - [x] 使用配置条件在真实体彩 Provider 与 Stub 之间切换。
  - [x] 用固定官方响应 fixture 编写适配器契约测试。
  - [x] 将 `match` 模块对齐 stableflow 角色分包，并同步设计文档。
  - [x] 定义 `AsianOddsProvider`、查询 Dto、比赛和盘口响应 Dto。
  - [x] 新增 `SportteryProviderProperties` 并应用于真实体彩客户端。
  - [x] 新增 `AsianOddsProviderProperties`。
  - [x] 配置连接超时、读取超时、重试、额度阈值和可选 API Key。
  - [x] 定义统一 Provider 错误分类，区分参数错误、限额、上游故障和解析失败。
  - [x] 补齐空比赛池、异常响应、未知状态和让球缺失测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=MatchQueryServiceTest,ChinaSportteryProviderTest,StubSportteryProviderTest test
  npm run backend:test
  ```

- 恢复入口：已完成；下一任务进入 T102。
- 执行记录：
  - 2026-07-22：为核对真实比赛先完成体彩查询契约与适配器；正式 Provider 基础任务等待 T004 后恢复。
  - 2026-07-23：恢复执行；范围含 match 角色分包、`MatchStatusEnum`、亚盘契约/配置、统一 Provider 错误分类与边界测试。
- 完成标准：
  - Provider 返回明确 Dto，不向业务层暴露原始 JSON。
  - 连接、读取、重试和额度阈值可配置。
  - API Key 不出现在 `toString`、日志或异常中。

- 验证记录：
  - 2026-07-22：体彩契约、真实/Stub 适配器及 3 个相关测试通过，提交 `67352d3`。
  - 2026-07-23：后端 27 个测试通过；完成角色分包、亚盘契约、`app.asian-odds` 配置脱敏与 Provider 错误分类。

### T102 Provider 与原始数据 migration

- 状态：`DONE`
- 优先级：P0
- 依赖：T003（`SKIPPED`；按替代约束验证 migration）
- 交付物：
  - `V1__init_provider_and_raw_data.sql`
  - Provider、原始响应和同步运行 Entity/Mapper
- 执行步骤：
  - [x] 按 `technical-design.md` 字段定义编写 `V1__init_provider_and_raw_data.sql`。
  - [x] 创建 Provider 配置、`raw_data_payloads`、`data_sync_runs` 表和约束。
  - [x] 为状态、数据类型和解析结果定义业务枚举。
  - [x] 创建 Entity、Mapper 与最小 Repository/Service 查询。
  - [x] 增加 JSONB、SHA-256、请求键和幂等唯一约束测试。
  - [x] 使用 Testcontainers 从空库执行 migration，并验证重复启动不重复执行。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MigrationTest,*PayloadHash*,*DataProvider* test
  npm run backend:test
  ```

- 执行记录：
  - 2026-07-23：开始执行；因 T003 跳过，不引入 Testcontainers；改用 migration SQL 契约测试、哈希单测，并在云端开发库启动验证 Flyway。
  - 2026-07-23：Testcontainers 步骤按 T003 替代约束完成——SQL 契约测试 + 云端开发库成功 apply V1。
- 完成标准：
  - migration 可从空库执行。
  - 原始响应保存 JSONB 和 SHA-256。
  - 重复响应由唯一约束去重。
  - migration 集成测试通过（或在 T003 跳过前提下完成文档约定的替代验证）。

- 验证记录：
  - 2026-07-23：后端 31 个测试通过（含 migration SQL 契约、SHA-256、枚举）；云端开发库 Flyway `Successfully applied 1 migration ... now at version v1`。

### T103 Stub 双数据源

- 状态：`DONE`
- 优先级：P0
- 依赖：T101
- 交付物：
  - 体彩比赛池、赛果和亚盘 fixtures
  - `StubSportteryProvider`
  - `StubAsianOddsProvider`
- 执行步骤：
  - [x] 实现稳定的 `StubSportteryProvider`，队名明确标注为演示数据。
  - [x] 编写 Stub 体彩比赛单元测试。
  - [x] 增加体彩比赛池、正常赛果、延期、取消和修正 fixtures。
  - [x] 增加亚盘正常、缺失、球队别名、时间冲突 fixtures。
  - [x] 实现 `StubAsianOddsProvider` 和赛果 Stub。
  - [x] 配置 test profile 强制使用 Stub，确保不会发起真实网络请求。
  - [x] 验证同一输入重复运行输出完全一致。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Stub*Test test
  npm run backend:test
  ```

- 恢复入口：T101 完成后先补 fixtures，再实现亚盘 Stub。
- 执行记录：
  - 2026-07-22：完成最小体彩 Stub，尚未进入双数据源和异常场景范围。
  - 2026-07-23：恢复 T103；范围：classpath fixtures、体彩赛果 Stub、`StubAsianOddsProvider`、test profile 强制 stub；验证 `*Stub*Test` 与 `npm run backend:test`。
- 完成标准：
  - 包含正常、缺失、别名、时间冲突、延期和取消样例。
  - dev/test profile 可明确切换到 Stub。
  - Stub 输出在重复运行时完全一致。

- 验证记录：
  - 2026-07-22：最小体彩 Stub 及稳定输出测试通过，提交 `67352d3`。
  - 2026-07-23：`*Stub*Test` 6 通过；`mvn -f backend/pom.xml clean test` 36 通过；前端未改动故未跑 `frontend:build`。

### T104 原始响应入库与同步运行服务

- 状态：`DONE`
- 优先级：P0
- 依赖：T102、T103
- 交付物：
  - `RawDataPayloadService`
  - `DataSyncRunService`
  - Provider 调用模板
- 执行步骤：
  - [x] 定义同步运行创建、成功、部分成功和失败状态机。
  - [x] 实现原始响应哈希、JSONB 保存、解析状态和错误信息追加。
  - [x] 实现 Provider 调用模板，固定“运行 -> 请求 -> 原始入库 -> 解析 -> 完成”顺序。
  - [x] 使用事务边界保证原始响应不会因领域解析失败而丢失。
  - [x] 增加重复响应、单条解析失败、整批失败和恢复测试。
  - [x] 记录请求耗时、记录数、重试次数和额度消耗。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*RawData*Test,*DataSyncRun*Test test
  npm run backend:test
  ```

- 执行记录：
  - 2026-07-23：开始 T104；范围：同步运行状态机、原始响应幂等入库、ProviderSyncTemplate、Mockito 单测（不连共享库）；验证 `*RawData*Test,*DataSyncRun*Test` 与 `npm run backend:test`。
- 完成标准：
  - 固定执行“创建运行 -> 请求 -> 原始入库 -> 解析 -> 完成运行”。
  - 解析失败保留原始响应和错误。
  - 单条失败不丢失整批成功结果。
  - 重复同步幂等测试通过。

- 验证记录：
  - 2026-07-23：`*RawData*Test,*DataSyncRun*Test` 13 通过；`npm run backend:test` 48 通过；前端未改动故未跑 `frontend:build`。
### T105 Provider 重试、限额与契约测试

- 状态：`DONE`
- 优先级：P0
- 依赖：T101、T104
- 交付物：
  - MockWebServer 契约/重试测试
  - 429/5xx/超时重试策略
  - 用量响应头解析和额度告警
- 执行步骤：
  - [x] 为体彩与亚盘 HTTP 客户端统一接入可配置超时。
  - [x] 定义仅对网络错误、429 和可恢复 5xx 生效的重试策略。
  - [x] 解析 `Retry-After` 和供应商额度响应头。
  - [x] 把每次尝试、最终状态和额度写入同步运行记录。
  - [x] 使用 MockWebServer/WireMock 覆盖 400、401、429、500、超时和非法 JSON。
  - [x] 检查日志、异常和测试快照没有 API Key、密码或 Cookie。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Provider*ContractTest,*Retry*Test test
  npm run backend:test
  ```

- 执行记录：
  - 2026-07-23：开始 T105；范围：共享 ProviderHttpExecutor、体彩接入重试/额度、亚盘 RestClient 超时装配、MockWebServer 契约测试；验证 `*Provider*ContractTest,*Retry*Test` 与 `npm run backend:test`。
- 完成标准：
  - 4xx 参数错误不重试。
  - 429 尊重 `Retry-After`。
  - 重试和额度消耗进入同步记录。
  - 凭据不进入 WireMock 快照或测试报告。

- 验证记录：
  - 2026-07-23：`*Provider*ContractTest,*Retry*Test`（含 ChinaSporttery/Template）通过；`npm run backend:test` 57 通过；前端未改动故未跑 `frontend:build`。

### T106 体彩候选源两周验证

- 状态：`PARTIAL`
- 优先级：P0
- 依赖：T104
- 交付物：
  - 中国大陆节点访问记录
  - 字段字典和脱敏样例
  - 连续两周比赛池与赛果报告
- 启动前置：
  - 在首次自动采集前记录负责人、开始日期、执行环境、本地与中国大陆节点、定时任务开关和第 14 天的决策日期；前置未齐全时不开始 14 天计时。
  - 若节点访问、授权调查或自动采集启动被外部条件拒绝，改为 `BLOCKED` 并记录证据、负责人和解除条件。
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
- 跟踪方式：自动每日采集真正启动后改为 `MONITORING`；连续 14 天结束后再改为 `DONE`、`PARTIAL` 或 `BLOCKED`。
- 决策期限：从首个自动采集日开始计算第 14 天，不以首次手工样本日期代替。
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

- 状态：`PARTIAL`
- 优先级：P0
- 依赖：T105
- 交付物：
  - The Odds API 实测接入
  - API-Football 或商业样例补测
  - 覆盖率、延迟、博彩公司和额度报告
- 启动前置：
  - 在首次自动采集前记录负责人、开始日期、验证 Key、额度/预算上限、执行环境和第 14 天的决策日期；密钥只能通过环境变量注入，前置未齐全时不开始 14 天计时。
  - 若 Key、预算或数据使用授权被外部条件拒绝，改为 `BLOCKED` 并记录证据、负责人、解除条件和可评估的替代供应商。
- 执行步骤：
  - [x] 回退此前无真实节点证据的 `DONE` 标记；完成 The Odds API `/v4/sports/{sportKey}/odds` 真实适配、受控原始载荷聚合与 `spreads` 严格配对解析。
  - [x] 以 `apiKey` 查询参数认证、固定 `eu/spreads/decimal`，仅根据当天体彩池的非敏感联赛映射查询唯一 `sportKey`；未配置联赛计入未覆盖。
  - [x] 记录 `x-requests-last` 实际额度，并在验证期累计 credits 加本轮估算会超过 400 时持久化受控预检失败，不请求上游。
  - [x] 配置生产验证节点的亚盘 12 小时采样、启动即采样及 13 小时新鲜度阈值；节点运行说明与每日证据表已写入 `data-sources.md`。
  - [x] 注册 The Odds API 验证账号，并在未版本化的受控本地 profile 配置 Key（不写入仓库或文档）。
  - [x] 拉取当天欧冠资格赛、巴甲的 `spreads` 市场，记录实际请求成本。
  - [x] 以每日体彩池为母集查询当天相关联赛，建立比赛映射样本并保留待复核状态。
  - [ ] 记录盘口值、主客赔率、博彩公司、更新时间和缺失原因。
  - [ ] 连续 14 天统计覆盖率、延迟、额度和接口错误率。
  - [ ] 对未覆盖比赛使用 API-Football 或一个商业样例源补测。
  - [ ] 核查历史数据、缓存、模型训练和产品展示授权。
  - [ ] 输出达到或未达到 90% 覆盖率的证据。
- 验证方式：每日记录母集数量、成功映射数、有效亚盘数和 credits；公式和样本写入 `data-sources.md`。
- 跟踪方式：取得验证 Key 并启动自动每日采集后改为 `MONITORING`；连续 14 天结束后再形成最终状态。
- 决策期限：从首个自动采集日开始计算第 14 天；Key、预算或授权阻塞必须记录解除条件。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*AsianOdds*ContractTest,*AsianOdds*SyncTest test
  rg -n "14 天|亚盘|覆盖率|credits|额度|授权" docs/data-sources.md
  ```

- 执行记录：
  - 2026-07-29：发现此前 `DONE` 没有中国大陆节点、真实 Key 或连续观测证据，回退为 `PARTIAL`。本次仅完成可本地验证的真实 The Odds API 适配与预算门禁；待节点负责人、Secret 引用、开始日、400 credits 预算和第 14 天决策日齐备并通过首个受控冒烟采集后，才可把 T106/T107 改为 `MONITORING`。
  - 2026-07-29：在项目负责人的授权下开始本机受控冒烟：使用本机 `application-local.yml` 的既有数据库/Redis 配置，先执行体彩池同步，再执行当天欧冠资格赛/巴甲的 The Odds API 真实 `spreads` 同步，验证 Provider、解析、原始载荷、映射和快照写入；不启动连续观测，不记录 Key。
  - 2026-07-29：冒烟中发现并修复三项项目问题：`@Scheduled` 不能直接解析 `30s` 等简写 `initialDelayString`；后台同步/预测状态的无数据源占位 Bean 在持久化 DataSource 已存在时抢先装配；The Odds 的时间筛选必须使用带秒的 UTC instant。三项修复均有单测或真实启动验证。

- 验证记录：
  - 2026-07-29：`TheOddsApiProviderTest`、Provider HTTP 契约/重试、亚盘同步和配置绑定专项测试通过；`npm run backend:test` 通过 409 项。覆盖 `apiKey` 查询认证、无 `Authorization`、200/4xx/429/超时/重试、实际额度头、400 credits 预检、受控 `spreads` 配对、未配置联赛与密钥不泄露。未改前端或 migration；真实节点冒烟采集因没有获授权的中国大陆节点与验证 Key 未执行，14 天计时未开始。
  - 2026-07-29：本机真实启动验证：管理端 health 为 `UP`；体彩池同步成功并从项目公共 API 读取当天 6 场。The Odds 成功运行 ID `4`：抓取/解析成功 12、失败 0、重试 0、实际 credits 2、精确关联载荷 1 份且解析状态 `SUCCESS`（SHA-256 见 `data-sources.md`）。运行前两次 422 失败未扣额度，时间格式修复后成功。中英文队名尚未确认映射，亚洲盘快照 0、公开详情显示 `PENDING` 映射；该状态符合“未确认不写快照”保护规则，不计作解析失败。测试服务已停止，14 天计时未开始。
  - 2026-07-29：`npm run backend:test` 通过 411 项（含新定时 Duration、无数据源后台查询装配和 The Odds UTC 时间参数断言）；`git diff --check` 通过。未运行前端验证，因为本次未改前端。

- 完成标准：
  - 目标竞彩比赛亚盘覆盖率至少 90%。
  - 盘口、主客赔率、来源和时间戳完整。
  - 月度预计用量与预算明确。
  - 不满足门槛时有替代供应商结论。

### T108 数据源 Go / No-Go 决策

- 状态：`BLOCKED`
- 优先级：P0
- 依赖：T106、T107、T207、T208
- 交付物：
  - 更新后的 `data-sources.md`
  - 供应商选择与回退策略
  - 数据授权结论
- 执行步骤：
  - [ ] 汇总 T106/T107 的覆盖率、准确率、延迟、稳定性、额度和授权证据。
  - [ ] 汇总 T207 端到端链路中的真实比赛映射准确率、标准化待处理量和人工复核比例。
  - [ ] 对每个候选供应商给出 Go、Conditional Go 或 No-Go。
  - [ ] 明确主 Provider、回退 Provider、故障降级和预算。
  - [ ] 更新配置、部署要求、风险清单和 `data-sources.md` 最终结论。
  - [ ] 由产品负责人确认生产数据展示与使用许可后签字验收。
- 验证方式：逐条核对 `data-sources.md` Go / No-Go 门槛，任何缺失证据都不能标记 `DONE`。
- 执行记录：
  - 2026-07-29：当前为预决策 `NO-GO`：T106/T107 尚无中国大陆节点连续观测，且缓存、模型训练和公开展示没有书面许可。待完成首个受控冒烟采集、14 天证据记录及书面许可后解除阻塞并形成可审计最终结论。
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

- 状态：`DONE`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V2__init_league_team_match_and_mapping.sql`
  - `V3__init_sporttery_and_asian_odds_snapshots.sql`
  - 对应 Entity/Mapper/Enum
- 执行步骤：
  - [x] 从 `technical-design.md` 提取联赛、球队、比赛、来源映射和快照字段。
  - [x] 编写 V2，创建联赛、球队、比赛和来源映射表。
  - [x] 编写 V3，创建体彩池快照和亚盘快照追加表。
  - [x] 增加业务唯一约束、状态检查、时间字段和必要索引。
  - [x] 创建 Entity、Mapper 和供应商无关枚举。
  - [x] 编写空库 migration、约束失败和重复执行测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MigrationTest,*ConstraintTest test
  npm run backend:test
  ```

- 执行记录：
  - 2026-07-24：开始 T201；范围：V2/V3 Flyway、match/odds Entity·Mapper·Enum、静态 SQL 契约测试（T003 跳过 Testcontainers）；验证 `*MigrationTest,*ConstraintTest` 与 `npm run backend:test`。
- 完成标准：
  - 体彩比赛、联赛、球队、来源映射和快照表完整。
  - 唯一约束和检查约束生效。
  - 快照表只追加。
  - migration 集成测试通过。

- 验证记录：
  - 2026-07-24：`*MigrationTest,*ConstraintTest,MatchMappingEnumsTest` 3 通过；`npm run backend:test` 60 通过；前端未改动故未跑 `frontend:build`；T003 跳过环境下以静态 SQL 契约替代空库 Flyway 集成。

### T202 体彩比赛池同步

- 状态：`DONE`
- 优先级：P0
- 依赖：T104、T201
- 交付物：
  - `SportteryPoolSyncService`
  - `SportteryPoolSyncJob`
  - 体彩快照写入
- 执行步骤：
  - [x] 定义按竞彩 `businessDate` 同步的输入 Dto 和同步结果。
  - [x] 从 `SportteryProvider` 读取比赛池并关联原始响应记录。
  - [x] 按体彩比赛 ID、竞彩日期和来源幂等创建内部比赛。
  - [x] 将 SP、让球和销售状态按采集时间追加为快照。
  - [x] 实现手动触发 Service，再接入带开关的定时 Job。
  - [x] 覆盖首次同步、重复同步、赔率变化、单场失败和空池测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SportteryPoolSync*Test test
  npm run backend:test
  ```

- 执行记录：
  - 2026-07-24：开始 T202；范围：扩展 HAD/HHAD SP 与 Stub raw、SportteryPoolSyncService/Job、Mockito 单测；验证 `*SportteryPoolSync*Test` 与 `npm run backend:test`。
- 完成标准：
  - Stub 比赛池可幂等同步。
  - 同一体彩编号和日期不重复建比赛。
  - SP 和销售状态变化生成新快照，不覆盖旧快照。
  - 任务运行和异常可追溯。

- 验证记录：
  - 2026-07-24：`*SportteryPoolSync*Test` 8 通过；`npm run backend:test` 68 通过；前端未改动故未跑 `frontend:build`。

### T203 联赛与球队标准化

- 状态：`DONE`
- 优先级：P0
- 依赖：T201
- 交付物：
  - 联赛标准化服务
  - 球队标准化服务
  - 已确认别名映射
- 执行步骤：
  - [x] 定义名称规范化规则：空白、全半角、大小写、标点和常见后缀。
  - [x] 建立联赛与球队的供应商外部 ID 映射优先规则。
  - [x] 建立人工确认别名表，保存来源、确认人和时间。
  - [x] 对未知名称只创建候选，不自动合并相似实体。
  - [x] 为中文/英文别名、同名球队和符号差异编写参数化测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Normalization*Test test
  ```

- 执行记录：
  - 2026-07-24：开始 T203；范围：NameNormalizationSupport、V4 别名表、League/TeamNormalizationService、参数化单测；验证 `*Normalization*Test` 与 `npm run backend:test`。
  - 2026-07-24：完成。`*Normalization*Test` 23 通过；`npm run backend:test` 91 通过。交付：规范化工具、V4 `league_aliases`/`team_aliases`、解析优先级（外部 ID → 别名 → 唯一精确名 → PENDING 候选）、`confirmAlias`。
- 完成标准：
  - 大小写、空白和常见符号标准化有测试。
  - 已确认外部 ID 优先于字符串匹配。
  - 不因名称相似直接合并不同球队。

### T204 双源比赛自动映射

- 状态：`DONE`
- 优先级：P0
- 依赖：T202、T203
- 交付物：
  - `MatchMappingService`
  - 置信度与解释字段
  - 待复核队列
- 执行步骤：
  - [x] 定义映射输入：来源比赛 ID、标准联赛、主客队和开赛时间。
  - [x] 先匹配已确认外部 ID，再计算名称与时间置信度。
  - [x] 为主客队反转、联赛冲突和时间超差设置硬性拒绝规则。
  - [x] 保存映射状态、分数、解释、方法和候选列表。
  - [x] 高置信度自动确认，其他记录进入待复核队列。
  - [x] 使用 Stub 和真实样本测试正确、缺失、反转、冲突和重复映射。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MatchMapping*Test test
  ```

- 执行记录：
  - 2026-07-24：开始 T204；范围：MatchMappingService、V5 解释/候选列、打分与硬拒绝、listPending、参数化单测。
  - 2026-07-24：完成。`*MatchMapping*Test` 20 通过；`npm run backend:test` 109 通过。交付：打分规则、AUTO/PENDING、V5 `mapping_explanation`/`mapping_candidates`、`listPending`。
- 完成标准：
  - 主客队、联赛和开赛时间共同参与映射。
  - 主客队反转和时间冲突进入待复核。
  - 外部比赛唯一映射约束生效。
  - Stub 自动映射准确率测试通过。

### T205 映射人工复核接口

- 状态：`DONE`
- 优先级：P0
- 依赖：T204、T004
- 交付物：
  - 映射列表、详情、确认和拒绝 Dto/Vo/API
  - 操作审计
- 执行步骤：
  - [x] 定义待复核列表筛选 Dto、详情 Vo、确认 Dto 和拒绝 Dto。
  - [x] 实现分页查询和候选差异展示所需聚合。
  - [x] 实现确认、拒绝和重新打开的业务状态机。
  - [x] 使用条件更新防止两名管理员并发确认冲突。
  - [x] 每次操作追加审计记录，不覆盖历史决定。
  - [x] 编写权限、参数、冲突、重复提交和审计测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MappingReview*Test test
  npm run backend:test
  ```

- 执行记录：
  - 2026-07-24：开始 T205；范围：MatchMappingReviewService、admin API、V6 audit_logs、条件更新与单测。
  - 2026-07-24：完成。`*MappingReview*Test` 12 通过；`npm run backend:test` 121 通过。交付：`POST /api/admin/provider/mappings/{list,detail,confirm,reject,reopen}`、条件更新状态机、V6 `audit_logs`、Security denyAll 验证；生产鉴权仍待 T601。
- 完成标准：
  - 低置信度记录可确认或拒绝。
  - 人工结果可被后续同步复用。
  - 确认冲突返回明确业务错误。
  - Controller 测试和 Service 测试通过。

### T206 亚盘快照同步

- 状态：`DONE`
- 优先级：P0
- 依赖：T104、T201、T204
- 交付物：
  - `AsianOddsSyncService`
  - `AsianOddsSyncJob`
  - 亚盘快照写入
- 执行步骤：
  - [x] 从亚盘 Provider 拉取目标联赛和时间窗内的赛前盘口。
  - [x] 关联原始响应、同步运行和已确认比赛映射。
  - [x] 拒绝未映射、低置信度、滚球或字段不完整的盘口。
  - [x] 按来源、博彩公司、比赛、盘口和采集时间追加快照。
  - [x] 解析并累计额度，输出当日覆盖率。
  - [x] 实现手动同步和带开关的定时 Job。
  - [x] 覆盖重复快照、盘口变化、映射缺失和额度不足测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*AsianOddsSync*Test test
  ```

- 执行记录：
  - 2026-07-24：开始 T206；范围：AsianOddsSyncService/Job/Writer、Provider raw、任务开关、AH+totals 写入与单测。
  - 2026-07-24：完成。`AsianOddsSyncServiceTest` 2 + `AsianOddsSnapshotWriterTest` 5 等通过；`npm run backend:test` 129 通过。交付：`fetchPreMatchOddsRaw`、映射门禁追加快照（AH+totals）、额度门禁、覆盖率、`app.tasks.asian-odds` Job。
- 完成标准：
  - 只给已确认比赛写盘口。
  - 来源、博彩公司、盘口、赔率和时间戳完整。
  - 重复快照幂等。
  - 覆盖率和额度可统计。

### T207 双源同步编排闭环

- 状态：`DONE`
- 优先级：P0
- 依赖：T006、T202、T203、T204、T206
- 交付物：
  - 体彩同步后的联赛/球队标准化回填
  - 体彩比赛与亚盘比赛映射编排
  - 历史未标准化比赛回填入口
  - Stub 端到端数据链路集成测试与覆盖率报告
- 执行步骤：
  - [x] 固定一批包含正常、别名、未知名称、映射冲突和缺盘场景的 Stub 业务日样本。
  - [x] 体彩比赛写库后调用联赛/球队标准化服务，幂等回填 `league_id`、`home_team_id`、`away_team_id`。
  - [x] 未确认标准实体只进入待处理队列，不通过模糊匹配静默合并。
  - [x] 亚盘同步优先复用标准实体和已确认比赛映射，低置信度数据继续由 T205 复核。
  - [x] 增加已有 `matches` 的受控回填入口，支持按业务日重跑且不覆盖人工确认结果。
  - [x] 串通 raw payload → matches → 标准实体 → match mapping → asian odds snapshot 全链路。
  - [x] 输出比赛数、标准化完成数、待处理数、映射确认数、有效盘口数和覆盖率。
  - [x] 覆盖重复运行、单场失败、事务边界和人工确认结果复用测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Pintegration -Dtest=*DataPipeline*Test,*Normalization*IntegrationTest verify
  npm run backend:test
  ```

- 完成标准：
  - 新同步比赛具有已确认标准实体 ID，或有明确的待处理状态和原因。
  - 已确认人工映射不会被自动同步覆盖。
  - Stub 单个业务日可从原始数据稳定生成标准比赛、映射和亚盘快照。
  - 重复执行不重复建实体、映射或相同快照。
  - 覆盖率分母、分子和缺失原因可从运行记录重建。
- 恢复入口：已完成；下一任务进入 T301。
- 执行记录：
  - 2026-07-25：开始执行；新增独立流水线定时任务和开关，保留原体彩/亚盘 Job 行为，使用 Stub 与 PostgreSQL Testcontainers 验证整链路。
  - 2026-07-25：[GitHub Actions #30165396038](https://github.com/ren997/jingcai-compass/actions/runs/30165396038) 在实现提交 `6551f40a31098edc45089f4b1b51fccc63eaeb91` 上通过；Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）。
  - 2026-07-25：157 个单元测试与 4 个 PostgreSQL 集成测试全部通过；`DataPipelineApplicationIT` 连续执行两次，验证 raw、标准实体、映射和相同盘口幂等，人工确认映射持续复用；M2 完成。
  - 2026-07-26：恢复为 `IN_PROGRESS` 做规范整改；范围为补齐业务枚举编码/描述契约、业务主流程顺序注释、公开模型字段说明、Service 方法契约以及模块包结构，功能行为与公共 API 路径保持不变。
  - 2026-07-26：本地 `mvn -B -ntp -f backend/pom.xml test` 共 157 个测试通过；规范整改提交 `0a204078db12e969727b1b6f48759ccb92afcbf9` 经 [GitHub Actions #30166212395](https://github.com/ren997/jingcai-compass/actions/runs/30166212395) 验证通过，T207 与 M2 收口为 `DONE`。

### T208 联赛与球队标准化复核闭环

- 状态：`IN_PROGRESS`
- 优先级：P0
- 依赖：T203、T205、T601、T602
- 交付物：
  - 体彩与 Provider 两侧独立的联赛、球队标准化输入与待复核队列
  - 管理员联赛/球队标准化复核 API 与后台页面
  - 经人工确认写入的 `provider_league_mappings`、`provider_team_mappings` 与追加审计记录
  - 映射评分对已确认联赛/球队关系的受控复用
- 执行步骤：
  - [x] 梳理体彩和 The Odds 两侧的联赛、球队外部标识、展示名与稳定规范化键；没有上游球队 ID 时，定义并测试 Provider 作用域内的持久化身份键，禁止使用一次性比赛 ID 充当球队别名。
  - [x] 将两侧标准化分别接入同步编排：体彩比赛先归一化为内部联赛/球队，Provider 事件先归一化为外部联赛/球队候选；未配置的 `sportKey`、未知球队和歧义名称均显式进入待复核，不得静默跳过。
  - [x] 新增 JWT 保护的联赛与球队复核列表、详情、候选比较、确认、拒绝和重新打开接口；确认必须基于当前外部身份、可见名称和明确选择的内部实体，采用条件更新并追加审计。
  - [x] 确认联赛时只写入对应的 `provider_league_mappings`；确认球队时只写入对应的 `provider_team_mappings`。如需别名记录，必须作为该同一次独立的联赛或球队确认的一部分写入，不能由比赛确认、开赛时间相近或既有候选自动生成。
  - [x] 新增懒加载后台复核入口，分别展示“联赛复核”和“球队复核”；页面同时显示 Provider 外部标识、原始展示名、规范化键、内部候选摘要、现有确认关系和审计历史，并要求二次确认。
  - [x] 调整比赛候选评分：只有已确认的联赛/球队映射才能提高分数或缩小候选范围；仍须保留主客方向、开赛时间和外部事件唯一性硬约束，不能把一次 `match_source_mappings` 确认当作联赛或球队别名证据。
  - [x] 覆盖并发确认、拒绝/重新打开、跨联赛同名球队、主客反转、未配置联赛、无上游球队 ID、历史单场确认不传播别名、后续新比赛复用已确认映射，以及无 DataSource、JWT 401/403 与 traceId。
  - [x] 比赛映射复核默认仅显示未开赛待复核项；已开赛项仅在明确的历史筛选中可见，保留审计但不作为当前运营待办。
  - [x] 服务端在确认时二次校验目标竞彩比赛尚未开赛，防止列表读取与操作之间跨过开赛时间后仍可确认；过期操作返回稳定错误与 traceId。
  - [x] 补齐前后端筛选、详情返回、过期禁用和 URL 可恢复测试；联赛、球队标准化复核不受该时效规则影响。
  - [x] 将体彩作为内部标准实体的唯一基线：`CHINA_SPORTTERY` 不进入 Provider 联赛/球队复核队列；页面仅复核外部 Provider 到竞彩标准实体的映射，既有体彩来源记录保留但不展示或操作。
  - [x] 统一赛事映射确认交互为弹窗内的确认按钮，不再要求输入固定文字；后台导航分别提供联赛复核和球队复核入口。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  git diff --check
  mvn -B -ntp -f backend/pom.xml -Pintegration verify
  ```

- 完成标准：
  - 体彩和 Provider 的联赛、球队各自经过可审计的标准化与人工复核；未确认项持续可见，不以匹配分数或单场确认冒充别名。
  - 每一条已确认的联赛/球队关系都能追溯到外部身份、内部实体、操作者和时间，并准确写入对应 `provider_*_mappings` 表。
  - 新到比赛能复用已确认的联赛/球队关系获得可靠候选与评分，但仍需独立满足赛事时间、主客方向和事件唯一性约束。
  - PostgreSQL 16 空库迁移、持久化约束与前后端复核流程均由 Draft PR 的 CI 验证通过。
- 恢复入口：开始前先核对现有 `provider_league_mappings`、`provider_team_mappings`、别名表和真实 The Odds 原始载荷中可用的外部身份字段；不得把现有已确认赛事映射批量反推为别名。
- 执行记录：
  - 2026-07-29：项目负责人确认作为下一项开发任务。背景：`provider_league_mappings` 当前为空，The Odds 的 `sportKey` 已保存为外部联赛标识但尚未经过联赛归一化；既有比赛确认仅确认“外部事件 → 竞彩比赛”，不自动写入联赛或球队别名。
  - 2026-07-30：开始执行；范围为受控外部身份元数据、The Odds `sport_key + 规范化队名` 球队键、独立标准化复核 API/后台页面、审计和映射评分复用。计划运行后端、前端、差异检查及 PostgreSQL 16 CI；保留用户现有 V15 注释空白改动，不纳入本任务。
  - 2026-07-30：实现完成，待 Draft PR 的 PostgreSQL 16 CI 收口。V16 只新增实时采集的展示名、规范化键与作用域字段，不反推旧记录；The Odds 新联赛/球队身份一律进入 PENDING，只有 `MANUAL_CONFIRMED` 映射才会传入赛事评分。`mvn -B -ntp -f backend/pom.xml clean test` 424 项通过；`cd frontend && npm run test && npm run build` 为 60 项通过并完成生产构建；`git diff --check` 通过。
  - 2026-07-30：提交 `6aed098` 已推送至 `origin/codex/t208-normalization-review`。创建 Draft PR 被外部权限阻塞：已授权 GitHub 连接器返回 `403 Resource not accessible by integration`（缺少 Pull requests 写权限），本机未安装 GitHub CLI，内置浏览器也尚未登录 GitHub。解除条件：项目负责人使用有仓库写权限的 GitHub 账号登录并创建 PR，或为连接器授予该仓库的 Pull requests 写权限；随后运行要求的 PostgreSQL 16 CI。任务改为 `BLOCKED`，不得启动 T106/T107 连续观测。
  - 2026-07-30：GitHub 授权刷新后，Draft PR [#20](https://github.com/ren997/jingcai-compass/pull/20) 已从 `codex/t208-normalization-review`（`f586276`）创建，权限阻塞解除。等待 GitHub Actions 执行 PostgreSQL 16/Testcontainers 验证；任务恢复为 `IN_PROGRESS`。用户现有 V15 注释空白改动未纳入 PR。
  - 2026-07-30：GitHub Actions [#82](https://github.com/ren997/jingcai-compass/actions/runs/30508345307) 首次 PostgreSQL 16 验证失败；原因是组件扫描早于 DataSource 注册，`ProviderNormalizationReviewServiceImpl` 的 `@ConditionalOnBean(DataSource.class)` 未装配，导致后台 Controller 缺少依赖。已在 `PersistenceServicesAutoConfiguration` 补齐同类持久化服务兜底工厂，并以自动配置回归测试覆盖。`mvn -B -ntp -f backend/pom.xml test` 425 项通过，`git diff --check` 通过；本机未运行 Docker，Testcontainers 集成验证继续以 PR CI 为准。
  - 2026-07-30：GitHub Actions [#83](https://github.com/ren997/jingcai-compass/actions/runs/30508911019) 已验证服务装配修复，但暴露两条随 V16 和“无/不完整盘口仅统计跳过”语义演进而过期的 IT 断言：空库迁移数仍写为 15，Stub 亚盘流水线仍预期 `PARTIAL` 并要求不存在的失败文本。已对齐为 V1～V16 和成功但含跳过记录的语义；提交前本地 `local` profile 已以替代端口 `18082` 完整启动，连接 PostgreSQL 并完成 Flyway V16 校验，`http://127.0.0.1:18083/actuator/health` 返回 `UP`；不停止占用默认 8080 的既有用户进程。
  - 2026-07-30：GitHub Actions [#84](https://github.com/ren997/jingcai-compass/actions/runs/30509482382) 成功执行 V1～V16 迁移和 40 个 IT，但 `DataPipelineApplicationIT` 仍把 T207 的 6 条已确认赛事映射当作预期；T208 现行约束下种子数据仅有 1 条人工赛事确认，其余 7 条外部事件保持 `PENDING`，不允许由球队/联赛别名反推。已将断言改为 `1/7`，待下一次 PostgreSQL 16 CI 验证。
  - 2026-07-30：GitHub Actions [#85](https://github.com/ren997/jingcai-compass/actions/runs/30509730515) 继续验证通过前述映射统计后，显示同一旧 T207 断言仍预期 5 条自动写入亚盘快照；T208 下仅预置的人工赛事确认可写入 1 条快照，另外 7 条因未确认标准化关系跳过，覆盖 1/2 场。已一并将快照、跳过数、覆盖率与幂等表计数对齐，待下一次 PostgreSQL 16 CI 验证。
  - 2026-07-30：GitHub Actions [#86](https://github.com/ren997/jingcai-compass/actions/runs/30509986918) 继续验证后显示旧 T207 IT 的 Provider 球队映射总数仍按亚盘自动扩散口径断言。T208 下只保留体彩独立标准化产生的两条映射，The Odds 未确认身份不产生自动复用关系；测试改为按映射来源和状态精确断言。
  - 2026-07-30：GitHub Actions [#87](https://github.com/ren997/jingcai-compass/actions/runs/30510213234) 显示前一版把表总数 `2` 错误归类为人工别名。实际两条为体彩比赛 B 的 `AUTO_CONFIRMED/EXACT_NAME` 映射，人工别名映射为 0；已将断言限定为该稳定的合法体彩基线，待本机容器与下一次 PostgreSQL 16 CI 验证。
  - 2026-07-30：本机 Docker Desktop 4.84/Engine 29 已安装并通过 `hello-world` 验证。项目原 Testcontainers 1.19.8 无法与该引擎的 Windows 命名管道握手；临时使用 1.21.4 后已在本机启动 PostgreSQL 16 容器并执行 `DataPipelineApplicationIT`。该测试继续暴露最后一项旧 T207 口径：未确认亚盘球队不再自动新建标准实体，预期球队数由 6 对齐为 4；将在相同本机容器命令复验后再提交依赖升级与测试修正。
  - 2026-07-30：将测试依赖固定升级至 Testcontainers 1.21.4；本机 Docker Desktop 4.84/Engine 29 已可直接执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`，完整通过 425 个单测和 41 个 PostgreSQL 16 集成测试。`DataPipelineApplicationIT` 现同时覆盖：1 条预置人工赛事确认、7 条待复核外部事件、1 条亚盘快照、2 条体彩 `AUTO_CONFIRMED/EXACT_NAME` 队伍映射、4 支未由亚盘自动扩展的标准球队以及第二次运行幂等。待提交后由 Draft PR 复验。
  - 2026-07-30：Draft PR [#20](https://github.com/ren997/jingcai-compass/pull/20) 的 [GitHub Actions #89](https://github.com/ren997/jingcai-compass/actions/runs/30511946842) 在提交 `d3bfe1d0448fe6e8f56984bc8f4d8f8d66a6b003` 成功。Ubuntu Runner 使用 Java 21.0.11、Maven 3.9.16 和 Testcontainers `postgres:16-alpine`（PostgreSQL 16.14）执行空库 Flyway V1～V16；425 个普通测试与 41 个 PostgreSQL 集成测试均通过。T208 与 M2 收口为 `DONE`，下一步为 T106/T107 的真实数据源连续观测启动。
  - 2026-07-30：根据项目负责人反馈恢复为时效修正。当前比赛映射页仅按 `PENDING` 查询，昨天已开赛的比赛仍占据运营队列；本次仅增加“当前/历史”复核范围与服务端过期开赛保护，不改 Provider、联赛/球队标准化、数据库结构或既有映射审计。计划运行后端、前端、差异检查和 PostgreSQL 16 集成验证。
  - 2026-07-30：项目负责人补充确认联赛/球队复核的主体口径：竞彩侧已完成的内部标准实体是基线，`CHINA_SPORTTERY` 不应作为待复核 Provider 项再次出现；仅人工确认外部 Provider（当前为 The Odds）到竞彩标准实体的关系。既有体彩来源映射不删除、不回填，仅从复核队列和操作入口排除。
  - 2026-07-30：时效与复核主体修正完成本地验证。比赛映射默认 `ACTIVE`（未开赛），`HISTORY` 仅保留证据且服务端拒绝确认已开赛目标；标准化队列与详情拒绝体彩来源，页面说明为“外部 Provider → 竞彩内部标准实体”。本机真实登录后的联赛复核接口和页面仅返回 1 条 `THE_ODDS_API` 项，`CHINA_SPORTTERY` 为 0。
  - 2026-07-30：修复联赛/球队内部候选查询的运行时回归：`candidates` 漏接收已校验的 `mappingId`，增量编译未重建旧类导致本地页面返回 500。已补回局部变量并增加联赛候选服务回归测试；不改变候选范围或复核主体。
  - 2026-07-30：按项目负责人确认调整标准化复核二次确认交互：保留选择实体后的确认弹窗，移除重复输入固定文字的门槛；在弹窗中点击确认按钮即提交，后端条件更新与追加审计不变。计划执行前端测试、生产构建与差异检查。
  - 2026-07-30：继续补齐映射复核操作一致性；范围为赛事关联弹窗直接确认，以及后台侧栏拆分“联赛复核”“球队复核”入口。计划执行前端测试、生产构建与差异检查；按项目负责人要求不运行本轮 PostgreSQL 集成测试。
-- 验证记录：
  - 2026-07-30：本地普通测试与前端构建已通过；PostgreSQL 16 空库迁移、Mapper 行为和并发条件更新仍以 Draft PR 的 `mvn -B -ntp -f backend/pom.xml -Pintegration verify` 为准，成功前任务保持 `IN_PROGRESS`。
  - 2026-07-30：`mvn -B -ntp -f backend/pom.xml test` 通过（425 项）；本地 `local` profile 启动与独立 Actuator 健康检查通过；`git diff --check` 通过。前端未改动，沿用实现提交的 Vitest 60 项与生产构建通过记录。修正断言推送后必须等待新 head 的 PostgreSQL 16 CI。
  - 2026-07-30：Docker Desktop 4.84.0 / Engine 29.6.2 本机启动；`hello-world` 通过。Testcontainers 1.21.4 下，`mvn -B -ntp -f backend/pom.xml -Pintegration verify` 通过（425 个单测、41 个 PostgreSQL 16 IT）；`cd frontend && npm run test && npm run build` 通过（11 个文件、60 项）；`http://127.0.0.1:18083/actuator/health` 返回 `UP`；`git diff --check` 通过。仍须等待 Draft PR 对同一提交的 CI。
  - 2026-07-30：同一提交的 GitHub Actions #89 成功；PostgreSQL 16.14 空库迁移 V1～V16、425 个普通测试和 41 个集成测试均通过，满足本任务 Draft PR CI 收口条件。
  - 2026-07-30：`npm run backend:test` 通过（430 项）；`cd frontend && npm run test && npm run build` 通过（11 个文件、60 项）；local profile 已在默认 `8080/8081` 启动并返回健康状态 `UP`。未运行本轮 PostgreSQL 集成测试，按项目负责人要求暂缓。
  - 2026-07-30：为防止增量编译掩盖候选查询错误，执行 `mvn -B -ntp -f backend/pom.xml clean test`，431 项通过；`cd frontend && npm run test && npm run build` 为 11 个文件、60 项通过且构建成功。本地管理员受保护候选接口实测返回成功及 3 个内部联赛候选（含“巴甲”）。本轮 PostgreSQL 集成测试仍按项目负责人要求未执行。
  - 2026-07-30：标准化复核弹窗改为按钮二次确认后，`cd frontend && npm run test && npm run build` 通过（12 个文件、61 项）；专项测试覆盖选择内部实体后无需输入固定确认文字、直接点击弹窗确认按钮提交。`git diff --check` 通过；未改后端或运行本轮 PostgreSQL 集成测试。
  - 2026-07-30：赛事映射的列表、竞彩比赛详情和外部赛事详情均改为弹窗内直接点击语义化确认按钮；左侧导航拆分为“联赛复核”“球队复核”。新增赛事关联弹窗回归测试；`cd frontend && npm run test && npm run build` 通过（13 个文件、62 项），未改后端，按项目负责人要求未运行本轮 PostgreSQL 集成测试。

## 8. M3 预测发布、锁定和快照

### T301 预测与快照 migration

- 状态：`DONE`
- 优先级：P0
- 依赖：T006、T201、T207
- 交付物：
  - `V7__init_prediction_and_public_snapshot.sql`
  - Prediction/Snapshot Entity、Mapper 和枚举
- 执行步骤：
  - [x] 固定完整预测契约：三项概率、让球胜平负倾向、预期总进球、置信等级、分析摘要、模型/特征版本和生成批次。
  - [x] 定义预测、预测版本、公开快照和存储对象元数据字段及可空规则。
  - [x] 编写 V7 migration；保留已执行的 V1～V6 原样，不重命名、删除或修改。
  - [x] 增加概率范围与概率和、状态、版本、哈希、发布时间和锁定时间约束。
  - [x] 设计同一比赛/模型多版本唯一约束和当前版本查询索引。
  - [x] 创建 Entity、Mapper、`PredictionStatusEnum` 和快照状态枚举。
  - [x] 编写空库迁移、非法概率、重复版本和状态约束测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Prediction*MigrationTest test
  ```

- 完成标准：
  - 概率、状态、版本和哈希约束完整。
  - 已发布版本可保留历史。
  - migration 集成测试通过。

- 执行记录：
  - 2026-07-26：开始执行；范围为 V7 预测/公开快照结构、Entity、Mapper、业务枚举及 PostgreSQL 约束验证，不提前实现导入、发布、锁定或快照文件生成。
  - 2026-07-26：本地 `*Prediction*MigrationTest` 2 个、全部普通测试 161 个通过；本机无 Docker，完整 PostgreSQL 验证由 GitHub Actions 执行。
  - 2026-07-26：[GitHub Actions #30166881993](https://github.com/ren997/jingcai-compass/actions/runs/30166881993) 在实现提交 `080f3de8cf791cb3303e9d1f0b3145ce2e27e2f7` 上通过；Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）。
  - 2026-07-26：161 个单元测试与 7 个 PostgreSQL 集成测试全部通过；空库成功执行 V1～V7 并停在版本 7，重复迁移为 0，概率容差、历史版本、Mapper 枚举往返、哈希及快照生命周期约束验证通过。

### T302 模型结果导入

- 状态：`DONE`
- 优先级：P0
- 依赖：T202、T301
- 交付物：
  - `PredictionImportDto`
  - 导入校验和服务
  - 离线模型样例文件
- 执行步骤：
  - [x] 定义包含比赛、模型/特征版本、三项概率、让球倾向、预期总进球、置信等级、分析摘要和生成时间的 `PredictionImportDto`。
  - [x] 校验比赛存在、未开赛、概率在区间内且概率和满足精度要求。
  - [x] 校验枚举、数值精度、摘要长度和禁止收益承诺等合规表达。
  - [x] 规范化小数精度，禁止以字符串或百分数字段混用。
  - [x] 实现整批校验后写入，任何失败不产生半批数据。
  - [x] 保存模型输入文件或批次哈希，支持重复导入幂等。
  - [x] P0 先完成结构化文件导入；受控模型命令入口保留为同一 Dto 的可替换输入适配器，不作为 T302 完成前提。
  - [x] 编写边界概率、概率和错误、非法文案、已开赛和重复批次测试。
  - [x] 经项目负责人确认后，在 GitHub Actions 的 PostgreSQL 16 临时容器中运行集成验证。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*PredictionImport*Test test
  mvn -B -ntp -f backend/pom.xml test
  mvn -B -ntp -f backend/pom.xml -Pintegration verify
  ```

- 完成标准：
  - 比赛、模型版本、概率边界和概率和校验完整。
  - 已开赛比赛拒绝导入。
  - 导入失败不生成半成品预测。

- 执行记录：
  - 2026-07-26：开始本地执行；范围为严格 JSON 文件解析、批次哈希、预测校验、DRAFT 整批事务导入和幂等测试，不增加 HTTP 接口、migration、发布逻辑或远程数据库连接。
  - 2026-07-26：完成 Parser/Service 接口与实现分离、DataSource 条件装配、固定 `Clock`、整批事务 Writer、离线样例和 PostgreSQL/Testcontainers IT；未修改 V1～V7 或公共 API。
  - 2026-07-26：[GitHub Actions #30189526953](https://github.com/ren997/jingcai-compass/actions/runs/30189526953) 在实现提交 `cec3c4fbc85fd6069876c4b6eb27949aed5a3c3e` 上通过；Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）。
- 验证记录：
  - 2026-07-26：`mvn -B -ntp -f backend/pom.xml -Dtest=*PredictionImport*Test test`，24 个测试通过。
  - 2026-07-26：`mvn -B -ntp -f backend/pom.xml test`，185 个普通测试通过；`*IT` 仅完成编译，本机无 Docker，未连接共享或远程数据库；本次仅修改后端，前端构建未重复运行。
  - 2026-07-26：GitHub Actions 执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`，185 个普通测试和 9 个 PostgreSQL 集成测试通过；其中 T302 导入 IT 2 个，验证原文件重放幂等、枚举/数值持久化和第二条约束失败时整批回滚。

### T303 预测发布和版本化重发

- 状态：`DONE`
- 优先级：P0
- 依赖：T302、T601
- 交付物：
  - `PredictionPublishService`
  - 发布 Dto/Vo/API
  - 内容规范化和 SHA-256
- 执行步骤：
  - [x] 定义发布请求 Dto、发布结果 Vo 和管理员 API。
  - [x] 管理员 API 只允许 T601 已认证管理员访问，未认证和越权操作不得进入发布 Service。
  - [x] 根据比赛时间计算锁定时间并校验仍可发布。
  - [x] 规范化预测内容，计算稳定 SHA-256。
  - [x] 发布 T302 已分配版本的 DRAFT；首次只允许 V1，重发按序发布下一草稿版本并保留旧版本。
  - [x] 使用事务与唯一约束处理并发发布。
  - [x] 追加发布审计并编写重复、重发、并发和过期测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*PredictionPublish*Test test
  ```

- 完成标准：
  - 发布写入时间、锁定时间、版本和哈希。
  - 重发发布新版本，旧版本不覆盖。
  - 并发发布结果一致且有测试。
- 执行记录：
  - 2026-07-26：从最新 `master` 创建 `codex/t303-prediction-publish`。范围固定为发布单条现有 DRAFT、严格连续版本、开赛时锁定、规范化 SHA-256、管理员 JWT 身份、追加审计及 PostgreSQL 并发验证；不新增 migration，不实现模型计算、导入 HTTP、锁定 Job、公开快照、公共 API 或前端。
  - 2026-07-26：实现提交 `4d311ba1e174e345dbf12171d95ece9a008088ff` 完成管理员发布 API、比赛/预测行锁、条件状态更新、连续版本、幂等复用、固定格式内容哈希和发布审计；本地专项 21 个、全部普通测试 225 个通过。
  - 2026-07-26：[GitHub Actions #30191772489](https://github.com/ren997/jingcai-compass/actions/runs/30191772489) 在提交 `11608837f3b9644a5551c10e857c788358129213` 上通过；Java 21、`postgres:16-alpine`（PostgreSQL 16.14），225 个普通测试和 12 个 PostgreSQL 集成测试全部通过。T303 新增 2 个 IT，验证同一草稿并发发布仅更新和审计一次、V1/V2 历史保留及审计异常时整事务回滚。

### T304 预测锁定

- 状态：`DONE`
- 优先级：P0
- 依赖：T303
- 交付物：
  - `PredictionLockService`
  - `PredictionLockJob`
  - 条件更新 SQL
- 执行步骤：
  - [x] 明确锁定时间边界和允许的状态迁移。
  - [x] 实现基于数据库当前时间/条件更新的批量锁定。
  - [x] 锁定后禁止修改比赛、模型版本、概率和核心预测内容。
  - [x] 定时 Job 只处理到期未锁定记录，并支持重复执行。
  - [x] 并发模拟发布、修改和锁定竞争，验证只有合法操作成功。
  - [x] 记录锁定数量、失败数量、耗时、异常指标和追加式审计。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*PredictionLock*Test test
  ```

- 完成标准：
  - 锁定前后边界测试完整。
  - 锁定后核心字段不能修改。
  - 重复任务幂等。
  - 多线程并发修改测试通过。
- 执行记录：
  - 2026-07-26：从最新 `master` 创建 `codex/t304-prediction-lock`。锁定任务使用 PostgreSQL 当前时间、`FOR UPDATE SKIP LOCKED` 和条件更新，不依赖 Redis；默认关闭，固定延迟 30 秒、初始延迟 15 秒、每批 100 条。新增 V9 数据库触发器硬性保护已发布和已锁定预测，成功锁定逐条追加审计，并通过低基数指标记录数量、延迟、耗时和异常；不新增 HTTP API、不产生新预测版本、不连接共享数据库。
  - 2026-07-26：实现提交 `95aeaf8f713ee9c6d05d5522a1910d4205680beb` 完成 V9、单条独立事务锁定、双开关 Job、逐条审计和 Micrometer 指标；本地专项 16 个、全部普通测试 245 个通过。
  - 2026-07-26：[GitHub Actions #30192865188](https://github.com/ren997/jingcai-compass/actions/runs/30192865188) 在实现提交上通过；Java 21、`postgres:16-alpine`（PostgreSQL 16.14），245 个普通测试和 17 个 PostgreSQL 集成测试全部通过。空库迁移到 V9，验证数据库不可变保护、到期边界、幂等、发布/修改/锁定竞争、并发 `SKIP LOCKED` 及审计失败单条回滚。

### T305 公开预测快照

- 状态：`DONE`
- 优先级：P0
- 依赖：T303
- 交付物：
  - `SnapshotStorage`
  - `LocalSnapshotStorage`
  - `PredictionSnapshotService`
  - `SnapshotPublishJob`
- 执行步骤：
  - [x] 定义公开快照 JSON schema 和字段排序/时间/小数规范化规则。
  - [x] 定义 `SnapshotStorage` 接口与本地文件实现。
  - [x] 从已发布预测生成规范化 JSON 和内容哈希。
  - [x] 先写临时对象并校验哈希，再原子发布并更新数据库状态。
  - [x] 同一事实重复生成必须得到相同字节和哈希。
  - [x] 覆盖写入失败、数据库失败、重复发布和损坏文件测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Snapshot*Test test
  ```

- 完成标准：
  - 规范化 JSON 可复算。
  - 同一事实生成相同哈希。
  - 文件和数据库哈希一致。
  - 文件写入失败不会标记快照成功。
  - 本地存储只作为开发期完整性验证；生产环境的不可覆盖存储和外部可验证发布由 T604/T605 验收，不把单库哈希宣传为防篡改证明。
- 执行记录：
  - 2026-07-26：从最新 `master` 创建 `codex/t305-public-snapshot`。范围固定为竞彩业务日当前公开预测的确定性 manifest、本地临时写入与原子发布、事务级 advisory lock、快照版本幂等和定时任务；默认每 5 分钟按需检查，不新增 migration、HTTP API 或前端，不连接共享数据库，公开下载与外部可信发布分别留到 T506、T604/T605。
  - 2026-07-26：实现提交 `5b301d7d2aac44320d7eb4a914e8c1d219bff151` 完成 manifest v1、单条预测哈希复算、本地不可覆盖存储、快照发布事务和双开关 Job；新增公开规范文档 `docs/snapshot-manifest-v1.md`。
  - 2026-07-26：[PR #8](https://github.com/ren997/jingcai-compass/pull/8) 的 [GitHub Actions #30197965181](https://github.com/ren997/jingcai-compass/actions/runs/30197965181) 使用 Java 21 和 `postgres:16-alpine`（PostgreSQL 16.14）通过；267 个普通测试和 22 个 PostgreSQL 集成测试全部通过，其中 T305 新增 5 个集成测试。空库迁移保持 V1～V9，验证 advisory lock 并发幂等、当前版本选择、文件与数据库哈希一致、事务回滚和失败重试。

## 9. M4 赛果与自动结算

### T401 比赛事实与结算 migration

- 状态：`DONE`
- 优先级：P0
- 依赖：T006、T205、T301、T601
- 交付物：
  - `V10__init_match_facts_and_settlements.sql`
  - `V11__add_core_indexes.sql`
  - MatchFact/Settlement Entity、Mapper 和枚举
  - 赛果事实源与结算版本规则
- 执行步骤：
  - [x] 明确 `match_result_facts` 为不可变的权威赛果来源，定义事实版本、来源原始响应、确认时间、替代关系和当前版本选择；`matches` 仅保留同事务更新的当前查询投影，不作为第二个可独立写入的赛果来源。
  - [x] 定义结算版本与当前版本规则：结算结果引用输入事实版本和规则版本，历史版本可保留；仅当前有效结算在 `(prediction_id, market_type)` 维度唯一。`PENDING` 为无当前有效结算时的派生展示状态，不通过原地修改旧结算记录实现状态迁移。
  - [x] 编写 V10 migration，创建比赛事实版本和结算表及业务约束；历史事实和结算核心内容不得由普通应用流程覆盖或删除。
  - [x] 复用 V6 已存在的 `audit_logs`；扩展赛果同步、结算和替代的应用审计枚举，不重复建表或修改 V1～V9。
  - [x] 编写 V11，只为 T402 的当前事实查询、T404 的待结算查询和已知历史查询增加有实际 SQL 依据的核心索引。
  - [x] 创建 Entity、Mapper、`SettlementStatusEnum`、事实版本/结算版本枚举；T401 不实现产生审计记录的同步或结算流程。
  - [x] 在 PostgreSQL 16 CI 验证事实权威性、当前结算唯一性与历史版本共存、审计不可普通覆盖，以及使用足量测试数据的索引查询计划。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*Settlement*MigrationTest,*Audit*Test test
  mvn -f backend/pom.xml -Pintegration verify
  git diff --check
  ```

- 完成标准：
  - 赛果修正只能追加新的事实版本，`matches` 当前投影与权威事实不会双写分叉。
  - 同一预测和市场仅有一个当前有效结算，但历史结算版本可以保留并追溯替代关系。
  - 审计表无普通覆盖或删除流程。
  - 待结算、历史和盘口查询索引有对应 SQL、查询计划和测试依据。
- 执行记录：
  - 2026-07-26：开始执行；范围为 V10/V11 赛果事实与结算版本化结构、数据库保护、实体/Mapper/枚举、审计枚举和 PostgreSQL 16 验证。不实现赛果同步、结算计算、自动结算、Controller、前端或修改 V1～V9。
  - 2026-07-26：实现完成待 CI。V10 在既有比分非空时以行数明确失败；赛果事实仅接受 `SPORTTERY_RESULT` 原始载荷，按版本追加并保护不可改删；结算仅持久化 `HIT`/`MISS`/`VOID`，`PENDING` 留给查询派生。V11 提供当前可结算事实、当前结算按事实反查和预测市场历史索引依据。
  - 2026-07-26：`mvn -B -ntp -f backend/pom.xml test` 通过（269）；`git diff --check` 通过。`npm run backend:test` 未执行（本机无 npm），已用其等价 Maven 命令替代。`mvn -B -ntp -f backend/pom.xml -Pintegration verify` 已触发但本机无 Docker，8 个 Testcontainers 用例均在 PostgreSQL 容器启动前失败；需在 CI PostgreSQL 16 环境回归后将 T401 置为 `DONE`。
  - 2026-07-26：实现提交 `279886bcdfb18d2e516a74c20b5a3913feaacc05` 已由 [PR #9](https://github.com/ren997/jingcai-compass/pull/9) 的 [GitHub Actions #30203999849](https://github.com/ren997/jingcai-compass/actions/runs/30203999849) 验证通过。Runner 使用 Eclipse Temurin Java 21.0.11、Maven 3.9.16、Testcontainers `postgres:16-alpine`（PostgreSQL 16.14）；269 个普通测试和 27 个 PostgreSQL IT 全部通过，其中 T401 新增 `SettlementMigrationApplicationIT` 的 5 个 IT。实际验证空库 Flyway 升级至 V11 且重复迁移为 0、旧比分 V9→V10 拒绝、赛果事实不可改删、同预测市场当前结算唯一与历史替代共存、赛果来源/比分/跨比赛约束、沿用 V6 的只追加审计流程，以及 5,000 条锁定预测数据上的当前事实/当前结算/历史索引 `EXPLAIN` 计划。

### T402 体彩赛果同步

- 状态：`DONE`
- 优先级：P0
- 依赖：T104、T202、T401
- 交付物：
  - `MatchResultSyncService`
  - `MatchResultSyncJob`
  - 比赛状态流转
- 执行步骤：
- [x] 为体彩赛果定义明确 Provider Dto 和状态映射。
- [x] 通过原始响应与同步运行模板拉取指定日期范围赛果。
- [x] 对有效官方赛果状态（含待确认）追加权威比赛事实；在同一事务中更新 `matches` 的当前查询投影，不直接覆盖历史事实。
- [x] 对延期、取消、未完成和异常比分执行显式状态迁移。
- [x] 官方修正创建新事实版本并追加审计，不覆盖旧事实。
- [x] 实现定时补数 Job 和正常/延期/取消/修正测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*MatchResultSync*Test test
  ```

- 完成标准：
  - Stub 正常、延期、取消和修正赛果可处理。
  - 非法状态回退被拒绝或进入异常。
  - 赛果修正保留审计。
- 执行记录：
  - 2026-07-26：开始 T402；范围为 Stub 赛果 raw 同步、版本化事实/当前投影事务、审计、7 天补数 Job 与 PostgreSQL 16 CI。真实体彩赛果接口、T404、Controller、前端和 V1～V11 migration 不在范围内。
- 验证记录：
  - 2026-07-26：`mvn -B -ntp -f backend/pom.xml -Dtest=*MatchResultSync*Test test` 通过 4 项；全量 `mvn -B -ntp -f backend/pom.xml test` 通过 318 项；`git diff --check` 通过。本机无 Docker，未连接共享或云端开发数据库。
  - 2026-07-26：实现提交 `43d55e86e1d358715c003aeee75ed202c7d2423c` 由 [PR #11](https://github.com/ren997/jingcai-compass/pull/11) 的 [GitHub Actions #30207146262](https://github.com/ren997/jingcai-compass/actions/runs/30207146262) 验证通过。Runner 使用 Eclipse Temurin Java 21.0.11、Maven 3.9.16、Testcontainers `postgres:16-alpine`（PostgreSQL 16.14）；318 个普通测试和 29 个 PostgreSQL IT 均通过，其中 T402 新增 `MatchResultSyncApplicationIT` 的 2 个 IT。实际验证空库 Flyway V1～V11 迁移与重复迁移为 0、Stub `SPORTTERY_RESULT` raw 存档和幂等重放、赛果事实版本链/单一 current/同事务比赛投影、历史事实不可覆盖、`SYNC`/`SUPERSEDE` 审计追加，以及比赛池不覆盖已有权威赛果投影。

### T403 市场结算器

- 状态：`DONE`
- 优先级：P0
- 依赖：T000（规则已固定；纯函数任务不依赖数据库 migration）
- 交付物：
  - 胜平负结算器
  - 体彩让球胜平负结算器
  - 赛果状态与结算资格规则矩阵
  - 参数化测试矩阵
- 执行步骤：
  - [x] 固定胜平负和体彩让球胜平负输入、输出与异常类型，并明确 `FINAL`、待确认和官方作废事实分别对应可计算、保持待结算和 `VOID` 的边界。
  - [x] 实现无数据库依赖的胜平负纯函数结算器。
  - [x] 实现正负整数让球的三结果纯函数结算器。
  - [x] 建立主胜、平、客胜以及让球后胜平负的参数化矩阵。
  - [x] 覆盖延期、取消/中止但未获官方作废结论、官方作废、缺失比分、非整数体彩让球和未知市场错误。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SettlementCalculatorTest test
  ```

- 完成标准：
  - 胜平负和体彩正负整数让球矩阵覆盖。
  - 计算器是纯函数，不读 Controller 或数据库。
  - 待确认、官方作废和异常市场输入均返回明确、可测试的结果或错误。
- 执行记录：
  - 2026-07-26：开始 T403；范围为 HAD/HHAD 纯函数结算器、资格规则矩阵与参数化单测。HAD 选项由调用方显式传入；不修改 Prediction、migration、T402/T404、Controller 或 Provider。
  - 2026-07-26：完成。新增显式市场结算输入、HAD/HHAD 独立计算器和无状态路由；`PENDING` 保持待结算，只有官方 `VOID` 返回作废，`FINAL` 才校验比分并计算。`mvn -B -ntp -f backend/pom.xml -Dtest=*SettlementCalculatorTest test` 通过 28 项；全量 `mvn -B -ntp -f backend/pom.xml test` 通过 297 项；`git diff --check` 通过。

### T404 自动结算任务

- 状态：`DONE`
- 优先级：P0
- 依赖：T304、T402、T403
- 交付物：
  - `SettlementService`
  - `SettlementJob`
  - 结算审计
- 执行步骤：
  - [x] 查询已锁定、比赛事实已确认且不存在当前有效结算的预测版本；公开层将这类记录派生展示为待结算。
  - [x] 按市场调用纯函数结算器并保存输入事实版本和规则版本。
  - [x] 使用当前有效结算唯一约束、版本关系和事务保证重复运行不重复生成当前结算。
  - [x] 单场事务失败只标记该场异常，继续处理其他比赛。
  - [x] 定时 Job 输出批次、成功、失败和待人工处理数量。
  - [x] 覆盖批量、重复、部分失败、未锁定和未完赛测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SettlementServiceTest,*SettlementJobTest test
  ```

- 完成标准：
  - 只结算已锁定且已确认完赛的预测。
  - 重复执行不重复结算。
  - 单场失败不阻塞整批。
  - 人工不能直接写结算结果。
- 执行记录：
  - 2026-07-27：开始执行；范围为结算候选查询、不可变结算写入、审计和默认关闭的定时 Job。计划执行 `*SettlementServiceTest,*SettlementJobTest`、完整普通测试、`git diff --check` 及 GitHub Actions PostgreSQL 16 集成验证；不修改 V1～V11 migration，不实现 T405 重算、Controller 或前端。
- 验证记录：
  - 2026-07-27：`mvn -B -ntp -f backend/pom.xml -Dtest=*SettlementServiceTest,*SettlementJobTest test` 通过 10 项；全量 `mvn -B -ntp -f backend/pom.xml test` 通过 329 项；`git diff --check` 通过。本机无 Docker，未连接共享或云端开发数据库；`SettlementApplicationIT` 的 3 个 PostgreSQL 16 用例待 Draft PR 的 GitHub Actions/Testcontainers 验证。
  - 2026-07-27：实现提交 `25bfd3b978a8d89be469b82548229866a1083136` 经 [PR #12](https://github.com/ren997/jingcai-compass/pull/12) 的 [GitHub Actions #30258263230](https://github.com/ren997/jingcai-compass/actions/runs/30258263230) 验证通过；后续 `a15c080`、`a9198a4`、`6bdb02a`、`1d53046` 仅修正装配与 PostgreSQL IT 建数/隔离。Runner 使用 Eclipse Temurin Java 21.0.11、Maven 3.9.16、Testcontainers `postgres:16-alpine`（PostgreSQL 16.14）；329 个普通测试和 32 个 PostgreSQL IT 均通过，其中 T404 新增 `SettlementApplicationIT` 的 3 个 IT。实际验证已锁定且当前 `FINAL`/`VOID` 事实才会结算、赛果事实与规则版本被写入结算、重复运行不新增当前结算、两市场结算和 `SETTLE` 审计同事务追加、缺少锁定前官方让球时保留待人工处理，以及未锁定/待确认事实不写结算。Flyway 空库迁移至 V11，重复迁移为 0；全程未连接共享或云端开发数据库。

### T405 赛果修正与结算重算

- 状态：`DONE`
- 优先级：P0
- 依赖：T404
- 交付物：
  - 事实版本和重算流程
  - 旧结算保留策略
- 执行步骤：
  - [x] 检测当前结算引用的事实版本与最新官方事实版本差异。
  - [x] 在受控事务中将旧结算关联为被替代并生成新的结算版本；旧结算的结果内容不得原地覆盖。
  - [x] 仅复用已记录的 `t403-v1` 规则版本；未知规则进入人工处理，不静默升级规则。
  - [x] 保存重算原因、操作者/任务、前后事实和前后结算关系。
  - [x] 明确公开查询的“赛果修正后重算”标识由 T507/T504 基于本任务保留的版本链与审计交付，不在 T405 新增公开接口或前端。
  - [x] 覆盖比分修正、`FINAL -> VOID` 状态修正、重复重算和并发测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*SettlementRecalculation*Test test
  mvn -f backend/pom.xml test
  git diff --check
  # GitHub Actions: mvn -B -ntp -f backend/pom.xml -Pintegration verify
  ```

- 完成标准：
  - 官方修正不会静默覆盖历史。
  - 新结算可追溯到输入事实和规则版本。
  - 结算版本链与审计可供 T507/T504 标识“赛果修正后重算”，不在本任务提前实现前端。
- 执行记录：
  - 2026-07-27：开始执行；范围为由既有默认关闭 `SettlementJob` 扫描触发的结算版本替代、同事务审计与 PostgreSQL 并发验证。只复用记录的 `t403-v1` 规则版本，不修改 V1～V11，不新增 Controller、公共 API 或前端；修正标识的对外建模交由 T507/T504。
  - 2026-07-27：完成。实现提交 `75fd539c2f11c6f97816df8eb989c2958365bf5a`，CI 集成断言修正提交 `a58e21d2872e21d1cac8ffe5e97e71133f1cfa4c`；[PR #13](https://github.com/ren997/jingcai-compass/pull/13) 的 [GitHub Actions #30260370908](https://github.com/ren997/jingcai-compass/actions/runs/30260370908) 在隔离 Testcontainers 环境通过。Runner 使用 Eclipse Temurin Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）；337 个普通测试和 35 个 PostgreSQL IT 全部通过，新增 `SettlementRecalculationApplicationIT` 为 3 个 IT。实际验证赛果修正后的 HAD/HHAD 版本替代、`FINAL -> VOID`、同结果/同事实重复扫描不重复追加、旧结算不可覆盖、单一 current、两市场历史与替代链共存、`SUPERSEDE` 前后快照审计、并发扫描仅生成一组新版 current；全套 CI 同时复验 Flyway 空库迁移至 V11 与重复迁移为 0、事实/结算保护以及索引查询计划。全程未连接共享或云端开发数据库。

## 10. M5 公共 API 与前端

### T501 公共比赛查询 API

- 状态：`DONE`
- 优先级：P0
- 依赖：T004、T202、T206、T207
- 交付物：
  - 比赛列表和基础详情 Dto/Vo/API
- 执行步骤：
  - [x] 建立显式 `MatchSummaryVo` 和按竞彩日期查询的公共比赛列表接口。
  - [x] 编写比赛列表 Controller 测试和 Service 映射测试。
  - [x] 接入 `ApiResponse`、错误码和 traceId。
  - [x] 将列表从实时 Provider 查询切换为 T202 持久化比赛池查询。
  - [x] 增加分页、日期、联赛、状态筛选和排序白名单。
  - [x] 定义并实现比赛基础详情 Vo/API，展示比赛事实、体彩 SP 和亚盘快照，明确区分体彩让球与亚洲盘。
  - [x] 展示同步时间、数据延迟、数据来源和映射/盘口缺失原因。
  - [x] 为列表和详情编写参数、空结果、排序白名单和 PostgreSQL 集成测试。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Dtest=*ControllerTest,*QueryServiceTest test
  npm run backend:test
  ```

- 恢复入口：已完成；下一任务进入 T503。
- 执行记录：
  - 2026-07-22：提前完成实时比赛列表原型，用于验证体彩 Provider；正式公共查询依赖尚未满足。
  - 2026-07-28：开始执行；范围为数据库列表/详情查询、明确分页筛选排序、赛事与盘口数据可用性说明及 PostgreSQL 16 集成测试。预计执行专项 Maven 测试、`npm run backend:test`、`git diff --check` 和 GitHub Actions 集成验证。
  - 2026-07-28：本地实现完成，保持 GET 兼容入口并新增持久化的 POST 列表/详情查询；详情逐维度保留最新体彩和亚盘快照、映射解释及稳定缺失状态。新增 Controller、Service 与 PostgreSQL 16 Testcontainers 覆盖，并补齐前端 `IN_PROGRESS`、`ABANDONED` 标签。未调用任何 Provider，未新增 migration。
  - 2026-07-28：实现提交 `48eab614d90bf4cb17af964628541f3f9dfbc25a` 已推送至 Draft PR [#15](https://github.com/ren997/jingcai-compass/pull/15)；[GitHub Actions #30334472450](https://github.com/ren997/jingcai-compass/actions/runs/30334472450) 的 PostgreSQL 16 Testcontainers 集成验证通过。
- 完成标准：
  - 分页、筛选和排序白名单生效。
  - 所有已知响应结构显式建模。
  - 列表和详情不在请求路径直接调用外部 Provider。
  - 体彩让球与亚洲盘字段、来源和时间戳明确区分。
  - Controller 与集成测试通过。

- 验证记录：
  - 2026-07-22：实时体彩比赛列表 API、显式 Vo 和 Controller 测试完成，提交 `21585e8`、`67352d3`。
  - 2026-07-25：规划校准时核对当前 Controller 和前端响应解析，确认已接入统一 `ApiResponse` 与 traceId；未运行代码测试。
  - 2026-07-28：`npm run backend:test` 通过（353 项普通测试）；`frontend/npm run test` 通过（12 项）；`frontend/npm run build` 通过；`git diff --check` 通过。本机 `mvn -Pintegration '-Dit.test=PublicMatchQueryApplicationIT' verify` 的普通测试 353 项通过，但 Testcontainers 因本机没有 Docker 失败；未以共享或云端开发数据库替代。
  - 2026-07-28：[GitHub Actions #30334472450](https://github.com/ren997/jingcai-compass/actions/runs/30334472450) 使用 Eclipse Temurin Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`，353 项普通测试和 40 项 PostgreSQL 集成测试全部通过，其中新增 `PublicMatchQueryApplicationIT` 为 3 项。空库 Flyway V1～V12 迁移成功，实际验证分页/筛选/稳定排序、体彩最新快照、亚盘逐来源/公司/让球线最新快照、映射与缺失状态，且请求路径不调用 Provider。

### T502 前端路由、请求和布局

- 状态：`DONE`
- 优先级：P0
- 依赖：T005
- 交付物：
  - Router
  - QueryClient
  - HTTP Service
  - Public/Admin Layout
- 执行步骤：
  - [x] 安装 React Router、TanStack Query 和 Ant Design。
  - [x] 初始化 BrowserRouter、QueryClient、Ant Design ConfigProvider 和全局错误边界。
  - [x] 创建公共布局、管理布局和 404。
  - [x] 创建统一 HTTP Client，处理基础 URL、JSON、超时和 traceId。
  - [x] 将服务端请求迁移到 Query hooks，统一 loading/error/stale 策略。
  - [x] 定义路由懒加载和公共/后台权限边界。
  - [x] 编写布局、路由、HTTP 错误和 QueryClient 冒烟测试。
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
- 恢复入口：先抽取统一 HTTP Client 和比赛 Query hook，再建立公共/后台布局与权限边界。
- 执行记录：
  - 2026-07-25：规划校准时核对现有代码，Router、QueryClient、Ant Design 和全局错误边界已存在，状态由 `TODO` 校正为 `PARTIAL`。
  - 2026-07-28：开始执行；范围为统一 HTTP Client、Query hooks、公共/后台路由布局、管理员登录会话和前端测试。预计执行 `npm run test`、`npm run build` 与 `git diff --check`。
  - 2026-07-28：完成。公共比赛页迁移到懒加载路由；新增类型化 HTTP Client、15 秒超时、traceId 错误、sessionStorage 管理员会话、登录/退出、受保护后台布局与 404；未改动后端接口或数据库。
- 验证记录：
  - 2026-07-28：`frontend/npm run test` 通过（2 个测试文件、12 项）；`frontend/npm run build` 通过；`git diff --check` 通过。覆盖公共路由、404、匿名后台跳转、登录回跳、退出、HTTP JSON/错误/traceId/Bearer/401/超时与调用方取消。

### T503 比赛列表与基础详情前后端

- 状态：`DONE`
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
  - [x] 抽取 Match API Service、Type 和 TanStack Query hooks。
  - [x] 增加联赛、状态筛选和稳定排序，筛选同步到 URL。
  - [x] 实现比赛详情路由和基础信息区。
  - [x] 分区展示体彩 SP、体彩让球和亚盘快照，禁止混用标签。
  - [x] 增加 stale/最后更新时间、刷新和上游故障提示。
  - [x] 编写列表交互、空态、错误、筛选、跳转和详情测试。
- 验证命令：

  ```bash
  cd frontend
  npm run test
  npm run build
  ```

- 恢复入口：T501/T502 `DONE` 后先抽 API Service 和 Query hook，再添加详情页。
- 执行记录：
  - 2026-07-22：完成比赛列表 UI 原型及真实数据源标识；路由、Query、筛选和详情未开始。
  - 2026-07-28：开始执行；范围为迁移到 T501 POST 列表/详情 API、URL 筛选分页、当天联赛下拉、基础详情及前端测试。保持后端接口、数据库和既有 QueryClient 策略不变；预计执行 `frontend/npm run test`、`frontend/npm run build`、`git diff --check`。
  - 2026-07-28：完成。公开列表已迁移到 POST `/list`，并通过 URL 恢复日期、联赛、状态、排序和分页；新增懒加载 `/matches/:matchId`，分区展示体彩 SP/官方让球、亚盘和来源映射。未改动后端接口、数据库或 QueryClient 策略。
- 完成标准：
  - 体彩让球与亚洲盘明确区分。
  - loading、empty、error、stale 状态完整。
  - 列表筛选和详情跳转测试通过。

- 验证记录：
  - 2026-07-22：比赛列表 Demo 与真实数据源标识完成，`npm run build` 通过，提交 `21585e8`、`67352d3`。
  - 2026-07-28：`frontend/npm run test` 通过（4 个测试文件、24 项）；`frontend/npm run build` 通过；`git diff --check` 通过。覆盖 POST 服务与取消/traceId、URL 默认/筛选/分页重置、联赛选项、详情分区/缺失/404、返回保留筛选和刷新状态。

### T506 公开预测详情前后端增量

- 状态：`DONE`
- 优先级：P0
- 依赖：T303、T305、T503
- 交付物：
  - 公开预测详情 Vo/API
  - 比赛详情页模型分析、透明信息和快照入口
- 执行步骤：
  - [x] 定义只返回当前公开版本的预测详情 Vo，同时提供历史版本和替代关系查询。
  - [x] 返回概率、让球倾向、预期总进球、置信等级和合规分析摘要。
  - [x] 返回发布时间、锁定时间、模型/特征版本、预测哈希、快照编号和锁定状态。
  - [x] 未发布、已替代或快照失败记录不得被错误展示为当前公开预测。
  - [x] 在比赛详情页增加模型分析和透明信息分区，并提供快照下载/校验入口。
  - [x] 编写未发布、重发、锁定、快照失败、历史版本和移动端测试。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  ```

- 完成标准：
  - 用户可追溯公开预测的发布时间、模型版本、哈希和快照。
  - 当前版本与历史版本不会混淆。
  - 未发布预测不会通过公共 API 泄漏。

- 执行记录：
  - 2026-07-28：开始执行；范围为公开预测详情、多模型当前版本与替代链、受控快照下载/校验，以及比赛详情页模型透明信息。复用既有预测与快照表，不新增 migration；计划执行 `npm run backend:test`、`frontend/npm run test`、`frontend/npm run build`、`git diff --check` 与 PostgreSQL 16 GitHub Actions 验证。
  - 2026-07-28：实现提交 `c2b6b5e2454d8d0c465ab0622af87421c0d16d1d`；新增 `/api/public/predictions/detail`、受控下载/校验接口和公开模型透明信息 UI。当前版本按每个 `matchId + modelVersion` 的最高公开版本选择；草稿、未验证或 manifest 不精确匹配的快照均不公开关联。
  - 2026-07-28：[Draft PR #16](https://github.com/ren997/jingcai-compass/pull/16) 的 [Actions #30340379691](https://github.com/ren997/jingcai-compass/actions/runs/30340379691) 首次通过。GitHub Actions 使用 Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）和 Flyway 10.10.0 运行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`：362 项普通测试、41 项 PostgreSQL IT 全部通过，其中 T506 新增 1 项。验证 V1～V12 空库迁移、同场多模型当前/历史选择、DRAFT 隔离、锁定预测、已发布 manifest 的预测 ID/哈希精确关联、损坏快照不可关联/下载及流式校验；未连接共享或云端数据库。

- 验证记录：
  - 2026-07-28：本地 `npm run backend:test`、`cd frontend && npm run test && npm run build` 与 `git diff --check` 通过；本机 Docker 不可用时 Testcontainers 不能启动，已由上述 GitHub Actions PostgreSQL 16 运行补足验收。

### T507 公开历史与统计 API

- 状态：`DONE`
- 优先级：P0
- 依赖：T404、T405
- 交付物：
  - 历史全量记录 Vo/API
  - 统计指标 Vo/API
  - Brier Score、Log Loss 与条件化 ROI 计算服务
- 执行步骤：
  - [x] 固定历史记录、预测版本、事实版本、结算版本和修正标识的 API 类型。
  - [x] 实现历史全量查询，不隐藏未命中、作废、待结算或已被修正的记录。
  - [x] 增加时间、联赛、模型版本、结算状态筛选和稳定分页。
  - [x] 固定 Brier Score、Log Loss、样本量和分组口径并编写纯函数测试。
  - [x] 仅在赔率来源、下注时点和固定下注规则完整时计算 ROI，否则返回不可用原因。
  - [x] 覆盖赛果修正、结算重算、空样本、分页稳定性和 PostgreSQL 查询计划。
- 验证命令：

  ```bash
  mvn -f backend/pom.xml -Pintegration -Dtest=*History*Test,*Statistics*Test verify
  npm run backend:test
  ```

- 完成标准：
  - 全量历史能重建预测、事实和结算版本关系。
  - 未命中记录不被隐藏，修正与重算不会静默替换历史。
  - 指标返回样本量、筛选口径和不可计算原因。

- 执行记录：
  - 2026-07-27：开始执行。范围为公开历史/统计 POST API、版本链与修正标识、纯函数指标和仅索引的 V12；不修改 V1～V11，不实现前端。先运行普通 Maven 测试，再以 Draft PR 在 GitHub Actions/Testcontainers PostgreSQL 16 验收，且不连接共享或云端开发数据库。
  - 2026-07-27：实现完成，等待 CI。新增 V12 索引、`/api/public/history/list` 与 `/api/public/statistics/summary`，历史保留预测/事实/HAD/HHAD 全部版本链并以 `SUPERSEDE` 审计标识赛果修正重算；统计只计当前事实和当前结算，ROI 返回结构化不可用原因。本地专项测试 8 项、完整 Maven 普通测试 345 项与 `git diff --check` 通过。当前环境未安装 Node/npm，故无法运行包装命令 `npm run backend:test`；已直接执行其后端 Maven 等价测试，未连接共享或云端数据库。
  - 2026-07-27：实现提交 `33b797a66aad0886752f1a5a109e46eef5575d2b`，随后以 `bd53dba7add719b859472b4c2317b8e4a03ee0be`、`020d9cb0f06ca35b86ba0d10b31f444243be0063` 修正持久化自动装配时序并补齐其测试，`5608ca0fff808c605fc113d23dcf4b33589a40bf` 校准 V12 实际查询计划。Draft PR [#14](https://github.com/ren997/jingcai-compass/pull/14) 的 [Actions #30271294353](https://github.com/ren997/jingcai-compass/actions/runs/30271294353) 首次全量通过。

- 验证记录：
  - 2026-07-27：GitHub Actions 使用 Java 21.0.11、Maven 3.9.16、`postgres:16-alpine`（PostgreSQL 16.14）执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`；345 个普通测试、37 个 PostgreSQL IT 均通过，其中 T507 新增 2 个 IT。验证空库 Flyway V1～V12 迁移及重复迁移 0 项，完整预测/事实/HAD/HHAD 版本链、当前记录筛选、修正重算标识、统计不重复计数与 V12 三个实际 `EXPLAIN` 索引计划；未连接共享或云端开发数据库。

### T504 历史与统计前后端

- 状态：`DONE`
- 优先级：P0
- 依赖：T502、T507
- 交付物：
  - 历史记录页
  - 统计分析页
  - Brier Score、Log Loss 和条件化 ROI 展示
- 执行步骤：
  - [x] 基于 T507 API 类型建立历史和统计 Query hooks。
  - [x] 实现历史列表 Query hook、分页和筛选 URL 状态。
  - [x] 展示预测版本、发布时间、赛果、命中/未命中和修正标识。
  - [x] 实现统计页时间范围、联赛和模型版本筛选。
  - [x] 展示样本量、Brier Score、Log Loss；仅在赔率和下注规则完整时展示 ROI。
  - [x] 编写未命中保留、筛选、空数据、指标缺失和移动端测试。
- 验证命令：

  ```bash
  cd frontend
  npm run test
  npm run build
  ```

- 完成标准：
  - 未命中记录不被隐藏。
  - ROI 未满足赔率和策略口径时不展示伪数值。
  - 按联赛、模型版本和时间范围筛选正确。

- 执行记录：
  - 2026-07-28：开始执行；范围为 T507 历史/统计 API 的前端类型、Query hooks、`/history` 和 `/statistics` 懒加载页面、URL 筛选分页、公共导航及窄屏布局。不修改后端接口、数据库或缓存策略；计划执行 `cd frontend && npm run test && npm run build` 与 `git diff --check`。
  - 2026-07-28：实现完成。公共导航新增历史/统计入口；`/history` 支持日期、联赛 ID、模型、锁定、HAD/HHAD 和结算状态筛选及分页，保留赛果/结算版本链与修正标识；`/statistics` 展示请求、近 7/30 天窗口、分组指标及 ROI 不可用原因。未修改 T507 API、后端或数据库。

- 验证记录：
  - 2026-07-28：`cd frontend && npm run test` 通过（7 个测试文件、41 项）；`cd frontend && npm run build` 通过；`git diff --check` 通过。覆盖 POST 服务/取消/traceId、URL 回退与筛选、历史未中/待结算/修正版本、统计窗口/分组/ROI 不可用、空态/错误态、懒加载路由与窄屏样式。

### T505 首页汇总

- 状态：`DONE`
- 优先级：P1
- 依赖：T503、T504、T506
- 交付物：
  - 首页指标卡片
  - 风险提示和历史入口
- 执行步骤：
  - [x] 定义首页聚合 Vo，所有指标可追溯到事实查询。
  - [x] 实现今日比赛、已发布、待结算和近期表现聚合。
  - [x] 展示数据最后更新时间、延迟、样本量和风险提示。
  - [x] 增加比赛、历史、统计入口和响应式布局。
  - [x] 对未达到数据口径的指标显示“暂无”，不填充伪数值。
  - [x] 编写聚合 Service 和前端窄屏/空态测试。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  ```

- 完成标准：
  - 指标可从事实数据重建。
  - 数据延迟和最后更新时间可见。
  - 移动端窄屏可阅读。

- 执行记录：
  - 2026-07-28：开始执行；范围为持久化事实驱动的公开首页聚合、`GET /api/public/home/summary`、根路由首页、指标/风险提示与响应式布局。场次按 `match_id` 去重，不调用 Provider、不新增 migration、不调整缓存策略；计划执行 `npm run backend:test`、`cd frontend && npm run test && npm run build` 与 `git diff --check`。
  - 2026-07-28：实现完成。新增 `home` 模块的匿名聚合接口、无 DataSource 统一降级与持久化自动配置；当天/历史公开预测按比赛去重，待结算仅计 HAD 未达终态的锁定预测比赛，7/30 天表现复用 T507 统计口径。根路由改为懒加载首页，补齐公共导航、数据新鲜度、快照时间、条件化 ROI/Yield、风险提示和窄屏布局；未新增 migration、缓存或 Provider 调用。

- 验证记录：
  - 2026-07-28：`npm run backend:test` 通过（367 项）；`cd frontend && npm run test` 通过（8 个测试文件、44 项）；`cd frontend && npm run build` 通过；`git diff --check` 通过。覆盖首页 GET/traceId/无数据源、上海日期、去重聚合、待结算与新鲜度、7/30 天统计透传、取消、根路由/导航、无指标/ROI 文案、错误刷新和窄屏样式。

## 11. M6 后台、可观测性与上线

### T601 管理员鉴权

- 状态：`DONE`
- 优先级：P0
- 依赖：T006、T302
- 交付物：
  - `V8__init_admin_accounts.sql`
  - Spring Security 配置
  - JWT 登录
  - 管理员角色
- 执行步骤：
  - [x] 明确管理员账号来源、密码哈希、Token 有效期和退出策略。
  - [x] 使用 V8 创建管理员账号和必要安全字段；保留 V1～V7 原样，不开放普通用户注册。
  - [x] 实现登录 Dto/Vo、认证 Service、JWT 签发与校验。
  - [x] 配置 `/api/public/**` 匿名只读、`/api/admin/**` 必须管理员权限。
  - [x] 对登录失败和权限拒绝使用统一错误响应并记录安全审计。
  - [x] 编写有效、过期、伪造 Token 及公共/后台边界测试。
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
- 执行记录：
  - 2026-07-26：开始本地执行；范围为 V8 管理员账号、环境变量首次引导、BCrypt 12、30 分钟可撤销 JWT、5 次失败锁定 15 分钟、统一 401/403、安全审计及后台操作者身份可信化，不增加前端、注册、刷新 Token 或远程数据库连接。
  - 2026-07-26：本地实现完成。专项 Security/Auth 等 23 个测试通过；`mvn -B -ntp -f backend/pom.xml test` 207 个普通测试全部通过。已编写 PostgreSQL 16 `AdminAuthApplicationIT` 并将空库迁移断言更新到 V8；按本机不运行 Docker、未获推送确认的约定，当时保持 `IN_PROGRESS` 并等待 GitHub Actions 临时 PostgreSQL 验证。
  - 2026-07-26：[GitHub Actions #30190932317](https://github.com/ren997/jingcai-compass/actions/runs/30190932317) 在修复提交 `0b987a1ebc9a6cbd89580b1e560efac1ad4d5cc0` 上通过；Java 21、`postgres:16-alpine`（PostgreSQL 16.14），207 个普通测试和 10 个 PostgreSQL 集成测试全部通过。`AdminAuthApplicationIT` 验证空库 V1～V8、BCrypt 密码哈希、登录访问、退出撤销、失败锁定及安全审计闭环；T601 完成，M6 进入 `PARTIAL`。

### T602 后台同步与映射复核页面

- 状态：`DONE`
- 优先级：P0
- 依赖：T205、T502、T601
- 交付物：
  - 同步运行页
  - 映射复核页
- 执行步骤：
  - [x] 实现后台同步运行列表、详情、错误和额度 API。
  - [x] 实现映射待复核列表、候选对比、确认和拒绝交互。
  - [x] 关键操作增加二次确认、权限校验和追加式审计。
  - [x] 原始响应只展示脱敏片段，不展示凭据、Cookie 或授权头。
  - [x] 编写权限、交互、冲突、错误和审计测试。
  - [x] 在公共导航提供受守卫的后台登录入口。
  - [x] 为 The Odds API 映射复核返回可读的外部主客队名，并精确回填既有记录。
  - [x] 将映射复核入口和详情改为以竞彩比赛为主体，展示并选择其可关联的外部比赛。
  - [x] 持久化并展示亚盘供应商原始开赛时间；仅按已保存的外部赛事 ID 精确回填历史记录。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  ```

- 完成标准：
  - 任务、额度、错误和待处理数量可见。
  - 关键操作要求鉴权并写审计。
  - 不展示未脱敏的原始凭据。
- 执行记录：
  - 2026-07-29：最终 PostgreSQL CI 验收通过。实现提交 `8189097f16c2b70cc773356c3caa4c31ad28c121` 在 [PR #19](https://github.com/ren997/jingcai-compass/pull/19) 的 [Actions #30440939018](https://github.com/ren997/jingcai-compass/actions/runs/30440939018) 成功；Ubuntu 24.04、Temurin Java 21.0.11、Maven 3.9.16 与 Testcontainers `postgres:16-alpine`（PostgreSQL 16.14）执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`。普通测试 417 项、PostgreSQL 集成测试 41 项均通过；空库成功执行 V1～V15，验证 V15 外部开赛时间列与精确回填迁移。T602 完成，M6 保持 `PARTIAL`，下一步为 T106/T107 连续观测与 T108 Go / No-Go。
  - 2026-07-29：本地实现完成。新增受 JWT 保护的 `/api/admin/provider/mappings/matches/detail` 与懒加载 `/admin/mappings/matches/:matchId`；列表详情入口以竞彩比赛为主体，官方开赛时间与外部候选的开赛时间并列展示，原 `/admin/mappings/:mappingId` 保留为单条映射的高级操作页。V15 新增 `external_kickoff_time`：后续亚盘同步直接持久化 `commence_time`，仅按 `THE_ODDS_API + external_match_id` 从已保存载荷精确回填旧记录；未重新调用 Provider，未暴露原始响应或凭据。等待本次提交的 PostgreSQL 16 CI。
  - 2026-07-29：继续完成“竞彩比赛主体”调整。列表已切换但详情仍是“外部映射 → 内部候选”，且 The Odds API 的 `commence_time` 只参与映射评分、未写入 `match_source_mappings`。本次新增竞彩比赛主体详情及受控 V15：后续同步持久化外部开赛时间，历史记录仅以 `THE_ODDS_API + external_match_id` 从既有原始载荷精确回填；详情并列展示官方与外部开赛时间，不展示原始 JSON 或重新调用 Provider。计划运行后端/前端测试、生产构建、`git diff --check`，并由现有 Draft PR 执行 PostgreSQL 16 集成验证。
  - 2026-07-29：开始“竞彩比赛主体”交互调整。当前复核列表按外部 `match_source_mappings` 行显示，不符合人工先核对竞彩比赛、再选择外部赛事的工作方式。本次将以 `matches` 作为列表主体，仅展示服务端已保留为该竞彩比赛候选的外部赛事；确认仍只允许该候选关系，保留 JWT、条件更新和追加审计。计划执行后端/前端测试、生产构建与 `git diff --check`。
  - 2026-07-29：开始映射复核可读队名修复。The Odds API 已解析 `home_team`/`away_team`，但同步把它们转换成 `NAME:<SHA-256>` 稳定键后，详情接口只返回该键，管理员无法核对英文队名。本次新增可读字段及受控回填：仅以 `THE_ODDS_API + external_match_id` 在已保存的亚盘原始响应中精确取出同一事件的主客队名；不展示原始 JSON、密钥、请求头或存储路径，不重新调用 Provider。计划执行后端/前端测试、`git diff --check`，并在 Draft PR 执行 PostgreSQL 16 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`。
  - 2026-07-29：本地实现完成。V14 新增外部主/客队展示名，后续同步保留哈希稳定键给归一化服务，同时持久化原始英文展示名；后台详情返回并优先展示名称。已在本机配置的既有 PostgreSQL 16 数据库实际执行 V14：映射 #12 按 `THE_ODDS_API + ecdcdc8d31ce5829bc5ff0bc1023346e` 精确回填为 `ŠK Slovan Bratislava` vs `FC Iberia 1999`，未发起任何 Provider 请求。等待 Draft PR 的 PostgreSQL 16 空库 CI。
  - 2026-07-29：实现提交 `f34be25` 已推送到 `codex/t602-mapping-provider-names`。创建 Draft PR 时，已授权 GitHub 插件返回 `403 Resource not accessible by integration`，本机未安装 GitHub CLI；工作流仅在 PR 或 `master` 触发，因此 PostgreSQL 16 CI 尚未运行。待授予插件 Pull requests 写入权限，或安装并认证 `gh` 后创建 Draft PR；任务保持 `IN_PROGRESS`。
  - 2026-07-29：用户创建 Draft [PR #19](https://github.com/ren997/jingcai-compass/pull/19) 后，提交 `0106f0ee312d7f21581bc7492b30b5ef4a4bef48` 的合并结果由 [Actions #30437153122](https://github.com/ren997/jingcai-compass/actions/runs/30437153122) 验证通过。Ubuntu Runner 使用 Temurin Java 21.0.11、Maven 3.9.16 与 Testcontainers `postgres:16-alpine`（PostgreSQL 16.14）执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`：412 个普通测试、41 个 PostgreSQL 集成测试均成功。空库 Flyway 从 V1 升级到 V14；验证了显示名字段迁移、The Odds API 按外部赛事 ID 的精确回填、同步时的可读名称持久化及后台详情返回，未调用真实 Provider、未暴露原始载荷或凭据。
  - 2026-07-29：导航入口补充开始。用户确认公共首页顶部未显示后台入口；范围为在公共导航新增受守卫的“后台登录”链接并补充前端路由断言，不修改后台权限、接口、数据库或现有后台业务。计划执行 `cd frontend && npm run test && npm run build` 与 `git diff --check`。
  - 2026-07-29：完成公共导航“后台登录”入口，固定指向 `/admin/login`，保留既有 JWT 登录和路由守卫；未改后端、数据库或接口。
  - 2026-07-29：实现提交 `f841b2750732453b63e972eb884337bb70a7e194` 已推送至 `codex/t602-mapping-list-fix`。Draft [PR #18](https://github.com/ren997/jingcai-compass/pull/18) 的 [Actions #30434074735](https://github.com/ren997/jingcai-compass/actions/runs/30434074735) 在 Ubuntu Runner、Temurin Java 21 与 PostgreSQL 16 集成环境中成功执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`；持久化服务/无数据源占位装配回归和映射列表真实记录一致性均已验证。本次无 migration、无 Provider 调用、无真实映射写入；M6 仍为 `PARTIAL`，下一任务为 T106/T107 连续观测与 T108 Go / No-Go。
  - 2026-07-29：本地修复完成，根因是组件扫描阶段的 `NoOpMatchMappingReviewService` 会在 DataSource 完成自动配置前错误命中，遮蔽持久化服务；同时移除后台同步/预测状态 fallback 对 MyBatis Mapper 的时序条件。无 DataSource 场景改由 `NoPersistenceAdminAutoConfiguration` 显式提供同一占位实现。以真实本地库验证 `match_source_mappings` 的 12 条 `THE_ODDS_API/PENDING` 记录和受保护列表接口均返回 12 条；未确认、拒绝或修改任何真实映射。后端服务可正常启动。`npm run backend:test`、`cd frontend && npm run test && npm run build`、`git diff --check` 均通过，等待本修复分支的 PostgreSQL 16 CI。
  - 2026-07-29：回归修复开始。真实 The Odds 冒烟后，公开比赛详情可读到 `PENDING` 的 `match_source_mappings`，但管理员 `/api/admin/provider/mappings/list` 返回 0 条。范围限于定位并修复后台复核列表与同一持久化映射事实不一致的问题，补充回归测试并用本机真实记录验证；不确认、不拒绝或修改任何真实映射，不改 migration、Provider 或前端业务范围。计划执行 `npm run backend:test`、`cd frontend && npm run test && npm run build`、`git diff --check`，并在 Draft PR 使用 PostgreSQL 16 集成验证。
  - 2026-07-28：开始；范围为 V13 同步运行—原始载荷精确关联、受 JWT 保护的同步运行/错误/额度查询、递归脱敏片段、候选比赛对比与仅候选确认，以及后台懒加载页面和交互。验证计划：`npm run backend:test`、`cd frontend && npm run test && npm run build`、`git diff --check`、`mvn -B -ntp -f backend/pom.xml -Pintegration verify`。
  - 2026-07-28：本地实现完成，V13 只建立精确运行—载荷关联且不回填历史；后台 API 不调用 Provider，前端操作均经 Bearer 请求与二次确认。修正无法安全解析的嵌套 JSON 不回显后，等待 Draft PR 的 PostgreSQL 16 集成验证。
  - 2026-07-28：实现提交 `f1b93513ae6b3016881f132eaf82dfae8fc42389` 已推送至 `codex/t602-admin-operations`，Draft [PR #17](https://github.com/ren997/jingcai-compass/pull/17) 的 [Actions #30348717331](https://github.com/ren997/jingcai-compass/actions/runs/30348717331) 通过，待本次任务板提交的最终检查通过后合并。

- 验证记录：
  - 2026-07-29：PR #19 的 Actions #30440939018 成功：PostgreSQL 16.14 空库迁移至 V15，`mvn -B -ntp -f backend/pom.xml -Pintegration verify` 通过（417 个普通测试、41 个集成测试）。本地后端健康检查、后端/前端测试、生产构建与差异格式检查亦已完成；T602 可合并。
  - 2026-07-29：`npm run backend:test` 通过 417 项；`cd frontend && npm run test` 通过 11 个文件/58 项；`npm run build` 通过；`git diff --check` 通过。本地后端重启后 `http://127.0.0.1:8081/actuator/health` 为 `UP`，Flyway 已应用 V15。未重复尝试本地管理员登录以避免账户失败锁定；受保护端点由 MVC 安全测试覆盖，等待 Draft PR 的 PostgreSQL 16 空库迁移验证。
  - 2026-07-29：本地 `npm run backend:test` 通过 412 项；`cd frontend && npm run test` 通过 11 个文件/55 项；`npm run build` 通过；`git diff --check` 通过。独立后端重启后，Flyway 成功从 V13 迁移到 V14，`http://127.0.0.1:8081/actuator/health` 为 `UP`；尚待 Draft PR PostgreSQL 16 集成验证。
  - 2026-07-29：CI 阻塞证据：`backend-integration.yml` 仅监听 PR 与 `master`；分支推送不触发该工作流。GitHub 插件创建 PR 返回 `403 Resource not accessible by integration`，且 `gh` 不可用。
  - 2026-07-29：`cd frontend && npm run test` 通过（11 个文件、54 项）；`npm run build` 通过；`git diff --check` 通过。
  - 2026-07-29：Draft PR #18 的 Actions #30434074735 成功：Ubuntu Runner、Temurin Java 21、PostgreSQL 16 上的 `mvn -B -ntp -f backend/pom.xml -Pintegration verify` 通过；本地后端、前端和真实只读列表验收同样通过。
  - 2026-07-29：回归修复本地检查通过：`npm run backend:test`、前端 Vitest 11 个文件/54 项、前端生产构建和 `git diff --check` 均成功；真实本地后端实例的管理员映射列表由 0 条恢复为 12 条，等待 PostgreSQL 16 CI。
  - 2026-07-29：Draft PR #19 的 Actions #30437153122 成功：Ubuntu Runner、Temurin Java 21.0.11、Maven 3.9.16、Testcontainers PostgreSQL 16.14 运行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify` 通过（412 个普通测试、41 个 IT）；V14 空库迁移、显示名精确回填与同步持久化均已复验。至此 T602 的本地和 CI 验收完整，M6 继续保持 `PARTIAL`，下一任务为 T106/T107 连续观测与 T108 Go / No-Go 决策。
  - 2026-07-28：本地 `npm run backend:test` 通过（380 项）；`cd frontend && npm run test` 通过（10 个测试文件、49 项）；`cd frontend && npm run build` 通过；`git diff --check` 通过。CI 中仍需执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify`，通过前任务保持 `IN_PROGRESS`。
  - 2026-07-28：GitHub Actions 在 Ubuntu Runner、Temurin Java 21、`postgres:16-alpine`（PostgreSQL 16.14）执行 `mvn -B -ntp -f backend/pom.xml -Pintegration verify` 通过：380 个普通测试与 41 个 PostgreSQL 集成测试全部成功。V13 空库迁移、去重载荷关联多次运行、后台 JWT/脱敏/额度与映射候选限制均覆盖；M6 保持 `PARTIAL`，下一任务 T603。

### T603 基础指标、日志与数据源告警

- 状态：`DONE`
- 优先级：P0
- 依赖：T104、T105
- 交付物：
  - Actuator/Micrometer 指标
  - 结构化关键日志
  - 数据源告警
- 执行步骤：
  - [x] 定义 API、Provider、同步与映射指标名称，并记录指标类型、标签基数和查询用途。
  - [x] 输出结构化日志并统一 traceId、jobName、providerCode 和业务 ID。
  - [x] 对密码、API Key、Authorization、Cookie 和原始响应敏感字段脱敏。
  - [x] 配置健康检查、数据库/Redis 状态和只暴露必要 Actuator 端点。
  - [x] 为覆盖率、额度、同步延迟、异常堆积和任务失败定义告警阈值。
  - [x] 使用故障注入验证日志、指标和告警实际触发。
- 验证命令：

  ```bash
  npm run backend:test
  curl http://127.0.0.1:8081/actuator/health
  curl http://127.0.0.1:8081/actuator/metrics
  curl http://127.0.0.1:8081/actuator/prometheus
  ```

- 完成标准：
  - 关键日志包含 traceId、jobName、providerCode 和业务 ID。
  - API Key、密码、Authorization 和 Cookie 被脱敏。
  - 覆盖率不足、额度耗尽和任务延迟可告警。
- 执行记录：
  - 2026-07-29：开始本地执行；范围固定为 Prometheus 指标、低基数标签、JSON 结构化日志与通用脱敏、独立内网 Actuator、数据库事实监测和规则先行的告警文档。不新增迁移、前端、通知渠道或 Testcontainers 验收；计划运行 `npm run backend:test`、本地 Actuator curl 与 `git diff --check`。
- 验证记录：
  - 2026-07-29：`npm run backend:test` 通过，389 项测试全部成功；含 Provider MockWebServer 的成功/4xx/429/5xx/超时/重试、同步/映射/数据源告警故障注入、通用脱敏、任务 MDC、数据库/Redis 健康贡献者和独立管理端口测试。
  - 2026-07-29：使用临时 JWT 且关闭 DataSource/Flyway/Redis 的隔离本地实例（不触碰共享开发库）执行 `curl http://127.0.0.1:8081/actuator/health`、`metrics`、`prometheus` 均返回 200；健康为 `UP`，指标目录和 Prometheus 文本暴露正常。
  - 2026-07-29：`git diff --check` 通过。

### T606 后台预测锁定与结算状态页

- 状态：`DONE`
- 优先级：P0
- 依赖：T304、T404、T502、T601
- 交付物：
  - 预测锁定状态页
  - 待结算与结算异常列表
- 执行步骤：
  - [x] 实现预测状态、锁定状态、待结算和结算异常的后台查询 API。
  - [x] 展示当前赛果事实版本、当前结算版本、可复算诊断原因和待人工处理数量，明确区分当前记录与历史版本。
  - [x] 实现后台筛选、详情和只读追溯交互；不提供人工直接写入结算结果的入口。
  - [x] 对所有后台查询实施权限校验；未来若增加重试或人工干预，必须增加二次确认与追加式审计。
  - [x] 编写权限、空态、异常、版本替代和移动端测试。
- 验证命令：

  ```bash
  npm run backend:test
  cd frontend && npm run test && npm run build
  ```

- 完成标准：
  - 管理员可识别锁定滞后、待结算和单场结算异常。
  - 当前与历史事实/结算版本不会混淆。
  - 页面不提供绕过自动结算流程的直接修改能力。

- 执行记录：
  - 2026-07-29：开始 T606；范围为管理员 JWT 保护的预测锁定、待赛果/待结算/需重算查询与只读版本追溯，新增后台分页与详情页面。仅从预测、比赛、赛果事实和结算持久化表派生状态；不新增 migration、不调用 Provider、不记录不存在的瞬时任务失败原因，也不提供人工结算、重试或修改入口。计划执行 `npm run backend:test`、`cd frontend && npm run test && npm run build` 与 `git diff --check`。
  - 2026-07-29：完成。新增 `/api/admin/prediction-status` 的锁定列表、结算列表和详情只读接口；逾期诊断采用 PostgreSQL 时间，结算页面仅以当前确认事实、HAD/HHAD 当前结算和事实引用关系派生待赛果、缺失与需重算状态。后台新增两组懒加载路由、URL 筛选、版本链追溯和窄屏布局。`npm run backend:test` 通过 398 项；`cd frontend && npm run test` 通过 54 项、`npm run build` 通过；`git diff --check` 通过。未新增 migration 或 CI/PR，M6 保持 `PARTIAL`，下一任务 T607。

### T607 预测、结算与快照业务指标

- 状态：`DONE`
- 优先级：P0
- 依赖：T304、T305、T404、T603
- 交付物：
  - 锁定、结算和快照业务指标
  - 对应结构化日志与告警规则
- 执行步骤：
  - [x] 在 T603 指标命名和脱敏约束下，补齐开赛后未锁定、待结算积压、单场结算失败、快照失败和哈希不一致指标。
  - [x] 在锁定、结算和快照任务日志中补齐 traceId、jobName 与业务 ID，不输出预测内容或敏感原始响应。
  - [x] 为锁定滞后、正常完赛后超时未结算和快照异常定义告警阈值与响应说明。
  - [x] 使用故障注入验证指标、日志和告警实际触发；缺失业务指标回到所属任务补齐，不在本任务复制业务状态判断。
- 验证命令：

  ```bash
  npm run backend:test
  curl http://127.0.0.1:8081/actuator/metrics
  curl http://127.0.0.1:8081/actuator/prometheus
  ```

- 完成标准：
  - 锁定、结算和快照异常可被区分、计数和告警。
  - 日志可关联到任务和业务记录，且不泄漏敏感数据。
  - 指标不使用无界业务 ID 或其他高基数标签。
- 执行记录：
  - 2026-07-29：开始执行；范围为锁定、结算和快照的低基数业务指标、数据库事实监测、JSON MDC 日志、Prometheus 规则及响应说明。不开新接口、前端、迁移或 Provider 调用；计划执行 `npm run backend:test`、隔离实例上的 Actuator metrics/prometheus 检查与 `git diff --check`。
  - 2026-07-29：完成。新增基于 PostgreSQL 当前时间的锁定逾期与确认赛果后结算积压事实 Gauge，任务关闭时保留观测但抑制状态告警；结算/重算与快照发布、哈希校验均使用固定低基数计数器。锁定、结算、快照 Job 统一输出 JSON MDC 的 `traceId`、`jobName`、状态和耗时，单条日志仅附 `predictionId` 或 `snapshotId`，不输出 Throwable、预测内容、原始载荷或对象路径。新增生命周期 Prometheus 规则和响应说明；`npm run backend:test` 通过 403 项，隔离实例的 health、metrics、prometheus 均为 200，`git diff --check` 通过；M6 保持 `PARTIAL`，下一任务 T604。

### T604 Docker 与 Nginx 部署

- 状态：`BLOCKED`
- 优先级：P0
- 依赖：T108、T505、T601、T603、T607
- 阻塞原因：T108 当前为 `NO-GO` 预决策；缺少 T106/T107 的 14 天中国大陆节点证据及缓存、模型训练、公开展示书面许可。仅在 T108 形成 `GO` 后解除。
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
  - [ ] 为生产预测快照配置不可覆盖或对象版本化存储，并提供匿名只读的外部校验位置。
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
  - 公开快照离开主数据库后仍可下载、复算哈希并追溯对象版本。
  - 从空环境按文档部署成功。

### T605 MVP 验收与连续运行

- 状态：`TODO`
- 优先级：P0
- 依赖：T108、T305、T405、T505、T602、T604、T606
- 交付物：
  - 验收报告
  - 连续运行报告
  - 备份恢复记录
  - 已知风险清单
- 执行步骤：
  - [ ] 按 `requirements-mvp.md` 逐条执行功能验收并保存结果。
  - [ ] 验证数据源达到 T108 Go 标准，未达标则停止生产发布。
  - [ ] 演练预测导入、发布、锁定、快照、赛果、结算和历史全链路。
  - [ ] 从外部公开位置重新下载预测快照，复算哈希并核对数据库记录和对象版本。
  - [ ] 使用接近目标规模的数据验证比赛列表/详情小于 2 秒、历史筛选小于 3 秒，并保存 p95 与查询计划。
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
- 外部前置（如适用）：
  - 负责人、凭据/节点、预算、决策日期或解除阻塞条件
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
- 观测记录：任务为 `MONITORING` 时必填，记录采集起止日、每日证据位置和决策日期。
- 阻塞说明：任务为 `BLOCKED` 时必填，说明证据和解除条件。
- 执行记录：
  - YYYY-MM-DD：开始，范围……
- 验证记录：
  - YYYY-MM-DD：命令……，结果……，提交 `<commit>`。
```

状态更新检查：

```text
开始前：依赖 DONE -> 本任务 IN_PROGRESS -> 更新当前活动任务
连续观测：任务 MONITORING -> 每日追加证据 -> 不占产品开发 WIP
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

当前正在修正 T208 的比赛映射复核时效：默认待办只保留未开赛比赛，历史项保留审计但禁止确认；联赛与球队标准化复核仍可独立处理历史 Provider 身份。修正验收后，M2 恢复 `DONE`，下一项才是启动 T106/T107 的真实数据源连续观测：先记录负责人、开始日、验证节点、密钥引用位置、400 credits 预算与第 14 天决策日，再开启每日采集并标记为 `MONITORING`。T401～T405、T501～T507、T602、T603、T606、T607 均已完成；M4/M5 已完成，M6 保持 `PARTIAL`。

T604 Docker 与 Nginx 部署被 T108 的 `GO` 结论硬性阻塞；在真实数据源上线条件满足后才提供容器化、网络隔离、备份与恢复演练。

T106/T107 应在 T208 完成后的真实映射复核基础上启动自动每日采集；开始计时前必须记录负责人、开始日、外部凭据/部署节点、预算上限和第 14 天决策日，启动后标记为 `MONITORING`，可与主线并存。它们不阻塞领域开发，但 T108 未达到 Go 标准时禁止生产部署。

随后严格按以下顺序推进：

```text
T301 -> T302 -> T601 -> T303
                        -> T304
                        -> T305

T301 + T601 -> T401
T000 -> T403（已完成）
已完成：T401 -> T403 -> T402
已完成：T304 + T402 + T403 -> T404
已完成：T404 -> T405
已完成：T507 公开历史与统计 API
已完成：T501 公共比赛查询 API、T502 前端路由、请求和布局、T503 比赛列表与基础详情前后端、T504 历史与统计前后端、T505 首页汇总、T506 公开预测详情前后端增量
已完成：T602 后台同步与映射复核页面
已完成：T606 后台预测锁定与结算状态页、T607 预测、结算与快照业务指标
已完成：T208 联赛与球队标准化复核闭环
下一步：启动 T106/T107 连续观测 -> T108 数据源 Go / No-Go 决策；T604 保持阻塞
```

## 15. 变更记录

| 日期 | 任务/提交 | 状态变化 | 验证或说明 |
| --- | --- | --- | --- |
| 2026-07-30 | T208 时效修正 | `DONE -> IN_PROGRESS` | 项目负责人确认已开赛比赛不应占用当前赛事映射复核队列。本次增加可恢复的当前/历史筛选，并在服务端确认动作实施开赛时效保护；联赛和球队标准化复核保持可处理历史身份。 |
| 2026-07-30 | T208 / `d3bfe1d0448fe6e8f56984bc8f4d8f8d66a6b003` | `IN_PROGRESS -> DONE` | [PR #20](https://github.com/ren997/jingcai-compass/pull/20) 的 [Actions #30511946842](https://github.com/ren997/jingcai-compass/actions/runs/30511946842) 成功：Java 21.0.11、Maven 3.9.16、Testcontainers PostgreSQL 16.14 执行空库 Flyway V1～V16；425 个普通测试和 41 个 PostgreSQL 集成测试通过。M2 完成；下一步按受控前置启动 T106/T107 连续观测。 |
| 2026-07-29 | T208 | `TODO` 新增并设为下一任务 | 补齐真实 Provider 的联赛与球队标准化人工复核闭环。体彩与 Provider 两侧须分别归一化；人工确认才写入 `provider_league_mappings`、`provider_team_mappings` 与审计，已确认赛事仅代表“外部事件 → 竞彩比赛”，严禁反推联赛或球队别名。M2 调整为 `PARTIAL`；T106/T107 连续观测等待此闭环后启动。 |
| 2026-07-29 | T602 / `f34be25` | `IN_PROGRESS -> DONE` | The Odds API 映射复核详情新增可读外部主/客队名；V14 仅按既有 `THE_ODDS_API + external_match_id` 从受控已存载荷精确回填，不调用 Provider、不泄露原始载荷或凭据。本地后端 412 项、前端 Vitest 55 项、生产构建和差异格式检查通过；[PR #19](https://github.com/ren997/jingcai-compass/pull/19) 的 [Actions #30437153122](https://github.com/ren997/jingcai-compass/actions/runs/30437153122) 在 Java 21.0.11、Maven 3.9.16、PostgreSQL 16.14 上通过 412 个普通测试与 41 个 IT。 |
| 2026-07-29 | T602 | `IN_PROGRESS -> DONE` | 公共导航新增“后台登录”，指向受守卫的 `/admin/login`；前端 Vitest 11 个文件/54 项、生产构建与差异格式检查通过。 |
| 2026-07-29 | T602 / `f841b27` | `IN_PROGRESS -> DONE` | 修复组件扫描过早选中无数据源映射占位服务导致后台待复核列表为 0 的回归；真实本地库的 12 条 `THE_ODDS_API/PENDING` 记录与受保护 API 一致，后端测试、前端 54 项、生产构建与差异格式检查通过；[PR #18](https://github.com/ren997/jingcai-compass/pull/18) 的 [Actions #30434074735](https://github.com/ren997/jingcai-compass/actions/runs/30434074735) 在 Java 21、PostgreSQL 16 上通过。 |
| 2026-07-29 | T107 | `IN_PROGRESS -> PARTIAL` | 本机受控真实冒烟完成：项目从未版本化 local profile 读取凭据；体彩池成功入库 6 场，The Odds 成功运行 ID 4 解析 12 条赛事、实际 2 credits、精确关联载荷 SHA-256 已记入 `data-sources.md`。修复 Duration 调度解析、后台持久化查询装配与 UTC 时间参数；队名映射仍待复核、未写亚洲盘快照，连续 14 天和书面授权尚未开始，因此 T108 仍为 No-Go、T604 继续阻塞。 |
| 2026-07-29 | T106/T107 | `T107 DONE -> PARTIAL` | 恢复证据任务：完成 The Odds API `apiKey` 查询认证、受控多 sport key 原始载荷、严格 spreads 配对、联赛映射未覆盖计数、实际 credits 与 400 credits 验证期预检门禁；真实中国大陆节点、Key、14 天观测和书面许可仍缺失，因此 T106/T107 不得标记 `MONITORING/DONE`，当前结论 `NO-GO`，T604 被 T108 阻塞。 |
| 2026-07-29 | T607 | `IN_PROGRESS -> DONE` | PostgreSQL 事实驱动的锁定逾期/结算积压 Gauge、结算/重算与快照低基数计数器、任务 JSON MDC、快照状态清除语义、Prometheus 规则与响应说明完成；后端 403 项、隔离 Actuator health/metrics/prometheus 与差异格式检查通过；M6 保持 `PARTIAL`，下一任务 T604。 |
| 2026-07-29 | T606 | `IN_PROGRESS -> DONE` | 管理员预测锁定、待赛果/待结算/需重算的持久化事实查询、当前/历史版本链、JWT 保护的只读 API、URL 可恢复后台路由和窄屏页面完成；后端 398 项、前端 Vitest 54 项、生产构建与差异格式检查通过；M6 保持 `PARTIAL`，下一任务 T607。 |
| 2026-07-29 | T603 | `IN_PROGRESS -> DONE` | Prometheus registry、仅本地绑定的独立 Actuator、低基数 Provider/同步/映射/任务/数据源指标、数据库事实监测、JSON MDC 日志与通用脱敏、Prometheus 规则和响应说明完成；389 项后端测试、隔离实例的 health/metrics/prometheus curl 与差异格式检查通过；M6 保持 `PARTIAL`，下一任务 T606。 |
| 2026-07-28 | T602 / `f1b9351` | `IN_PROGRESS -> DONE` | V13 精确同步运行—原始载荷关联、JWT 保护的同步/错误/额度查询、递归脱敏、后台同步与映射复核页面、候选限制确认和二次确认完成；[PR #17](https://github.com/ren997/jingcai-compass/pull/17) 的 [Actions #30348717331](https://github.com/ren997/jingcai-compass/actions/runs/30348717331) 在 Java 21、PostgreSQL 16.14 通过 380 项普通测试与 41 项 IT；M6 保持 `PARTIAL`，下一任务 T603。 |
| 2026-07-28 | T505 | `IN_PROGRESS -> DONE` | 新增数据库事实驱动的首页 API 与根路由、按比赛去重的当天/历史预测和待结算聚合、T507 近 7/30 天表现、新鲜度/快照时间、风险提示和窄屏布局；后端 367 项、前端 Vitest 44 项、生产构建与差异格式检查通过；M5 完成，下一任务 T602。 |
| 2026-07-28 | T504 | `IN_PROGRESS -> DONE` | 新增历史/统计公共页面、T507 类型与 Query hooks、URL 筛选分页、完整赛果/结算版本链、ROI 不可用口径和窄屏布局；前端 Vitest 41 项、生产构建与差异格式检查通过；M5 保持 `PARTIAL`，下一任务 T505。 |
| 2026-07-28 | T506 / `c2b6b5e` | `IN_PROGRESS -> DONE` | 公开预测详情、当前/历史版本链、受控快照流式下载与校验、比赛详情模型透明信息完成；[PR #16](https://github.com/ren997/jingcai-compass/pull/16) 的 [Actions #30340379691](https://github.com/ren997/jingcai-compass/actions/runs/30340379691) 在 PostgreSQL 16.14 通过 362 项普通测试与 41 项 IT，其中 T506 为 1 项；M5 保持 `PARTIAL`，下一任务 T504。 |
| 2026-07-28 | T503 | `IN_PROGRESS -> DONE` | 公共比赛页切换到 T501 POST 列表/详情 API，完成 URL 筛选分页、当天联赛下拉、体彩/亚盘/映射分区详情和窄屏布局；前端 Vitest 24 项、生产构建与差异格式检查通过，下一任务 T506。 |
| 2026-07-28 | T501 / `48eab61` | `IN_PROGRESS -> DONE` | 持久化公开比赛 GET 兼容入口、POST 列表/详情、缺失状态和映射解释完成；[PR #15](https://github.com/ren997/jingcai-compass/pull/15) 的 [Actions #30334472450](https://github.com/ren997/jingcai-compass/actions/runs/30334472450) 在 PostgreSQL 16.14 通过 353 项普通测试与 40 项 IT，其中 T501 为 3 项；M5 保持 `PARTIAL`，下一任务 T503。 |
| 2026-07-28 | T502 | `IN_PROGRESS -> DONE` | 公共/后台布局、404、懒加载路由、sessionStorage 管理员登录会话和统一 HTTP Client 完成；Vitest 12 项及生产构建通过，下一任务 T501。 |
| 2026-07-27 | T507 / `33b797a`、`bd53dba`、`020d9cb`、`5608ca0` | `IN_PROGRESS -> DONE` | V12、公开历史/统计 API、完整版本链与赛果修正标识、Brier/Log Loss/分组和 ROI 不可用口径完成；[Actions #30271294353](https://github.com/ren997/jingcai-compass/actions/runs/30271294353) 在 PostgreSQL 16.14 通过 345 个普通测试和 37 个 IT，其中 T507 为 2 个 IT；M5 保持 `PARTIAL`，下一任务 T502。 |
| 2026-07-27 | T405 / `75fd539`、`a58e21d` | `IN_PROGRESS -> DONE` | 默认关闭的结算 Job 先重算过期结算，再扫描待结算；旧结算保留、`t403-v1` 重算、`SUPERSEDE` 审计、并发幂等完成。[Actions #30260370908](https://github.com/ren997/jingcai-compass/actions/runs/30260370908) 在 PostgreSQL 16.14 通过 337 个普通测试和 35 个 IT，其中 T405 为 3 个 IT；M4 完成，下一任务 T507。 |
| 2026-07-27 | T404 / `25bfd3b` | `IN_PROGRESS -> DONE` | 自动结算候选扫描、独立事务、HAD/HHAD 结算、不可变结算追加、审计和默认关闭 Job 完成；[Actions #30258263230](https://github.com/ren997/jingcai-compass/actions/runs/30258263230) 在 PostgreSQL 16.14 通过 329 个普通测试和 32 个 IT，其中 T404 为 3 个 IT；下一任务 T405 |
| 2026-07-26 | T402 / `43d55e8` | `IN_PROGRESS -> DONE` | Stub raw 赛果同步、不可变版本化事实/当前投影、审计和 7 天补数 Job 完成；[Actions #30207146262](https://github.com/ren997/jingcai-compass/actions/runs/30207146262) 在 PostgreSQL 16.14 通过 318 个普通测试和 29 个 IT，其中 T402 为 2 个 IT；下一任务 T404 |
| 2026-07-26 | T403 | `IN_PROGRESS -> DONE` | 新增 HAD/HHAD 纯函数结算器、状态资格规则和 28 项参数化测试；全量普通测试 297 项及差异格式检查通过，下一任务 T402 |
| 2026-07-26 | 任务规划 v0.4 | 未开始任务重排 | 明确赛果事实源与结算版本模型；T403 提前；拆分 T602/T603，并新增 T606/T607；未修改任何 `DONE` 任务或既有 migration |
| 2026-07-22 | T000 | `DONE` | 建立需求、数据源、技术设计、实施指南和执行看板 |
| 2026-07-22 | T001 / `9563693` | `DONE` | 云端开发连接、配置约定和启动验证完成 |
| 2026-07-22 | T501、T503 / `21585e8` | `TODO -> PARTIAL` | 首个比赛列表 Demo，后端测试和前端构建通过 |
| 2026-07-22 | T101、T103、T106、T501、T503 / `67352d3` | `PARTIAL` | 重构查询分层、接入真实体彩比赛池并校准文档；后端 5 个测试、前端构建通过 |
| 2026-07-22 | T000 / 文档 v0.2 | `DONE` | 将本文件升级为逐步执行手册；当前无活动任务，下一任务 T002 |
| 2026-07-22 | T002 | `PARTIAL -> DONE` | 完成生产配置、类型安全配置、HTTP 超时、Actuator/OpenAPI 和 10 个后端测试；当时下一任务为 T003（后决定跳过） |
| 2026-07-22 | T003 | `TODO -> SKIPPED` | 项目负责人决定不安装本地 Docker；保留测试禁连共享云数据库约束，下一任务 T004 |
| 2026-07-22 | T004 | `TODO -> DONE` | 完成统一响应、异常、traceId、分页、审计、安全和 OpenAPI；后端 19 个测试、前端构建及端点启动验证通过 |
| 2026-07-22 | T005 | `PARTIAL -> DONE` | 完成 Ant Design、TanStack Query、Vitest/Testing Library、错误边界和 lockfile 验证；前端 3 个测试、构建及后端回归通过 |
| 2026-07-23 | T101 | `PARTIAL -> DONE` | 对齐 stableflow 角色分包与文档；完成亚盘契约/配置、Provider 错误分类与边界测试；后端 27 个测试通过 |
| 2026-07-23 | T102 | `TODO -> DONE` | 完成 V1 migration、data 模块 Entity/Mapper/Service；SQL 契约与哈希测试通过；云端库 Flyway 应用到 v1 |
| 2026-07-23 | T103 | `PARTIAL -> DONE` | classpath Stub fixtures、体彩赛果 Stub、`StubAsianOddsProvider`、test profile 强制 stub；后端 36 个测试通过 |
| 2026-07-23 | T104 | `TODO -> DONE` | 同步运行状态机、原始响应幂等入库、`ProviderSyncTemplate`；后端 48 个测试通过 |
| 2026-07-23 | T105 | `TODO -> DONE` | ProviderHttpExecutor 重试/额度、体彩接入、亚盘 RestClient 超时装配、MockWebServer 契约；后端 57 个测试通过 |
| 2026-07-24 | T201 | `TODO -> DONE` | V2/V3 migration、match/odds Entity·Mapper·Enum、静态 SQL 契约；后端 60 个测试通过 |
| 2026-07-24 | T202 | `TODO -> DONE` | 体彩比赛池同步、HAD/HHAD SP、Stub raw、条件追加快照与 Job；后端 68 个测试通过 |
| 2026-07-24 | T203 | `TODO -> DONE` | 名称规范化、V4 别名表、联赛/球队标准化与 confirmAlias；Normalization 23、后端 91 测试通过 |
| 2026-07-24 | T204 | `TODO -> DONE` | MatchMappingService、V5 解释/候选、打分与待复核；MatchMapping 20、后端 109 测试通过 |
| 2026-07-24 | T205 | `TODO -> DONE` | 映射复核 API、条件更新状态机、V6 audit_logs；MappingReview 12、后端 121 测试通过 |
| 2026-07-24 | T206 | `TODO -> DONE` | 亚盘快照同步、AH/totals 写入、额度门禁与 Job；AsianOddsSync 相关与后端全量测试通过 |
| 2026-07-25 | 任务规划 v0.3 | 规划校准 | 保留现有代码和 V1～V6；新增 T006/T207/T506/T507，后续 migration 调整为 V7～V10，提前 T601 并拆分公共产品依赖 |
| 2026-07-25 | T006 | `IN_PROGRESS -> DONE` | GitHub Actions 使用 PostgreSQL 16.14 空库执行 V1～V6；129 个单元测试和 3 个数据库集成测试通过；M0 完成 |
| 2026-07-25 | T207 | `IN_PROGRESS -> DONE` | 双源流水线、受控标准化回填、映射复用、盘口快照和覆盖率报告完成；157 个单元测试和 4 个 PostgreSQL 集成测试通过；M2 完成 |
| 2026-07-26 | T207 规范整改 | `IN_PROGRESS -> DONE` | 补齐枚举编码/描述、业务步骤注释、公开 VO 和模块包结构；本地 157 个测试及 PostgreSQL 集成 CI 通过 |
| 2026-07-26 | T301 / `080f3de` | `IN_PROGRESS -> DONE` | V7、预测/快照实体与枚举完成；161 个普通测试和 7 个 PostgreSQL 集成测试通过 |
| 2026-07-26 | T302 / `cec3c4f` | `TODO -> DONE` | 严格 JSON 导入、批次幂等和整批事务完成；185 个普通测试、9 个 PostgreSQL 16.14 集成测试通过 |
| 2026-07-26 | T601 / `0b987a1` | `IN_PROGRESS -> DONE` | V8、管理员 BCrypt 12、可撤销 JWT、失败锁定、安全审计和可信操作者身份完成；207 个普通测试、10 个 PostgreSQL 16.14 集成测试通过 |
| 2026-07-26 | T303 / `1160883` | `IN_PROGRESS -> DONE` | 管理员预测发布、连续版本、并发幂等、规范化哈希和发布审计完成；225 个普通测试、12 个 PostgreSQL 16.14 集成测试通过 |
| 2026-07-26 | T304 / `95aeaf8` | `IN_PROGRESS -> DONE` | V9 不可变触发器、PostgreSQL 到期抢占、独立事务、逐条审计和锁定指标完成；245 个普通测试、17 个 PostgreSQL 16.14 集成测试通过 |
| 2026-07-26 | T305 / `5b301d7` | `IN_PROGRESS -> DONE` | 确定性公开快照、单条哈希复算、本地原子发布、advisory lock 和版本幂等完成；267 个普通测试、22 个 PostgreSQL 16.14 集成测试通过；M3 完成 |
| 2026-07-26 | 新会话恢复与 CI 交付规范 | 文档完善 | 固化 Git/任务看板恢复入口、CI 分支判断、Draft PR、两轮 Actions、失败排查、证据记录、合并同步和授权复用流程 |
| 2026-07-26 | T401 / `279886b` | `PARTIAL -> DONE` | V10/V11、不可变版本化赛果事实、结算历史/当前唯一、核心索引及 Entity/Mapper/Enum 完成；[Actions #30203999849](https://github.com/ren997/jingcai-compass/actions/runs/30203999849) 在 PostgreSQL 16.14 通过 269 个普通测试和 27 个 IT，其中 T401 为 5 个 IT；M4 进入 `PARTIAL` |
