# 竞彩罗盘开发任务表

## 1. 目标

本文件是 MVP 的执行看板，把需求、数据源方案、技术方案和开发手册拆成可领取、可验证的任务。

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

## 2. 使用说明

状态定义：

- `TODO`：未开始。
- `IN_PROGRESS`：正在执行，同一执行者尽量只维护一个进行中任务。
- `BLOCKED`：有明确外部阻塞，并在任务下说明原因。
- `DONE`：代码、测试、文档和完成标准均已满足。

执行规则：

- 开始任务前确认所有依赖已经在代码中完成。
- 任务实现方式以 `implementation-guide.md` 对应章节为准。
- 数据源供应商最终选型以 `data-sources.md` 的 Go / No-Go 标准为准。
- 状态不能只根据“文件存在”判断，必须运行任务列出的验证命令。
- 新增范围先更新 `requirements-mvp.md`，再增加任务。

## 3. 当前进度

| 里程碑 | 状态 | 说明 |
| --- | --- | --- |
| M0 工程基线 | `IN_PROGRESS` | 云端开发连接和基础测试已完成，配置分层与 Testcontainers 待完成 |
| M1 Provider 基础 | `IN_PROGRESS` | 体彩查询契约、真实适配器和最小 Stub 已完成，其余 Provider 基础能力待完成 |
| M2 标准化与映射 | `TODO` | 依赖基础表和 Provider 契约 |
| M3 预测发布闭环 | `TODO` | 可使用 Stub 比赛数据开发 |
| M4 赛果与结算 | `TODO` | 依赖锁定预测和最终赛果 |
| M5 公共 API 与前端 | `IN_PROGRESS` | 比赛列表纵向切片已完成，详情、历史和统计待完成 |
| M6 后台、稳定性与上线 | `TODO` | 真实上线受数据源选型阻塞 |

## 4. M0 工程基线

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
- 完成标准：
  - 技术栈固定为 PostgreSQL 16、Redis 7、Spring Boot 和 React/Vite。
  - 数据源验证与业务开发边界明确。
  - M0～M6 有编号化任务和完成定义。

### T001 云端 PostgreSQL 与 Redis 开发接入

- 状态：`DONE`
- 优先级：P0
- 依赖：T000
- 交付物：
  - `application-local.example.yml`
  - Git 忽略的 `application-local.yml`
  - `.gitignore` 中的本机密钥配置规则
- 完成标准：
  - 云端 PostgreSQL 和 Redis 的端口可访问且认证成功。
  - 真实地址和密码不进入 Git 暂存区或提交历史。
  - 后端使用 `local` profile 读取开发连接。
  - 自动化测试明确禁止使用共享云端数据库。

### T002 后端依赖与配置分层

- 状态：`IN_PROGRESS`
- 优先级：P0
- 依赖：T001
- 交付物：
  - 补齐 `backend/pom.xml`
  - `application.yml`
  - `application-local.example.yml`
  - `application-prod.yml`
  - Provider、任务、Redis、Flyway、Actuator 和 SpringDoc 配置
- 完成标准：
  - 配置通过环境变量覆盖。
  - 生产配置没有密钥默认值。
  - PostgreSQL 驱动和 Flyway PostgreSQL 模块可解析。
  - local profile 能连接云端开发 PostgreSQL 和 Redis。

### T003 Testcontainers 与上下文测试

- 状态：`TODO`
- 优先级：P0
- 依赖：T002
- 交付物：
  - PostgreSQL Testcontainer 测试配置
  - `application-test.yml`
  - 修复后的 `JingCaiCompassApplicationTests`
- 完成标准：
  - 不启动本机 PostgreSQL 也能运行测试。
  - 测试 profile 关闭真实 Provider 和定时任务。
  - Flyway 能在测试容器空库执行。
  - `npm run backend:test` 通过。

### T004 公共后端基础设施

- 状态：`TODO`
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
- 完成标准：
  - 参数校验和业务异常返回统一格式。
  - 日志和响应能关联同一 traceId。
  - 分页大小有上限。
  - 后台路径在 T601 完成前默认拒绝。
  - `/actuator/health` 和 Swagger 在 local profile 可用。
  - 基础设施有 Controller/配置测试。

### T005 前端依赖与测试基线

- 状态：`IN_PROGRESS`
- 优先级：P1
- 依赖：T000
- 交付物：
  - Ant Design
  - TanStack Query
  - Vitest + Testing Library
  - 前端独立 `package-lock.json`
  - 测试初始化文件
- 完成标准：
  - `npm run frontend:build` 通过。
  - 前端测试命令可执行并至少有一个 App 冒烟测试。
  - 干净克隆使用 `npm ci` 能还原相同依赖。

## 5. M1 Provider 基础与数据源验证

### T101 Provider 契约与配置

- 状态：`IN_PROGRESS`
- 优先级：P0
- 依赖：T004
- 交付物：
  - `SportteryProvider`
  - `AsianOddsProvider`
  - Provider Properties
  - 内部请求/响应 Dto
- 完成标准：
  - Provider 返回明确 Dto，不向业务层暴露原始 JSON。
  - 连接、读取、重试和额度阈值可配置。
  - API Key 不出现在 `toString`、日志或异常中。

当前进度：已完成 `SportteryProvider`、明确 DTO、真实比赛池适配器和 Provider 切换；亚盘契约、配置属性、超时与重试仍未完成。

### T102 Provider 与原始数据 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V1__init_provider_and_raw_data.sql`
  - Provider、原始响应和同步运行 Entity/Mapper
- 完成标准：
  - migration 可从空库执行。
  - 原始响应保存 JSONB 和 SHA-256。
  - 重复响应由唯一约束去重。
  - migration 集成测试通过。

### T103 Stub 双数据源

- 状态：`IN_PROGRESS`
- 优先级：P0
- 依赖：T101
- 交付物：
  - 体彩比赛池、赛果和亚盘 fixtures
  - `StubSportteryProvider`
  - `StubAsianOddsProvider`
- 完成标准：
  - 包含正常、缺失、别名、时间冲突、延期和取消样例。
  - dev/test profile 可明确切换到 Stub。
  - Stub 输出在重复运行时完全一致。

当前进度：已完成最小 `StubSportteryProvider`，亚盘、赛果和异常场景 fixtures 尚未完成。

### T104 原始响应入库与同步运行服务

- 状态：`TODO`
- 优先级：P0
- 依赖：T102、T103
- 交付物：
  - `RawDataPayloadService`
  - `DataSyncRunService`
  - Provider 调用模板
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
- 完成标准：
  - 4xx 参数错误不重试。
  - 429 尊重 `Retry-After`。
  - 重试和额度消耗进入同步记录。
  - 凭据不进入 WireMock 快照或测试报告。

### T106 体彩候选源两周验证

- 状态：`IN_PROGRESS`
- 优先级：P0
- 依赖：T104
- 交付物：
  - 中国大陆节点访问记录
  - 字段字典和脱敏样例
  - 连续两周比赛池与赛果报告
- 完成标准：
  - 比赛池字段完整率 100%。
  - 正常完赛场次赛果可获取率 100%。
  - 延期、取消和修正场景有记录。
  - 使用许可和生产访问风险有明确结论。

当前进度：2026-07-22 已核对比赛池接口、响应字段和页面数据；连续两周、赛果、异常场景与授权结论仍未完成。

### T107 亚盘候选源两周验证

- 状态：`TODO`
- 优先级：P0
- 依赖：T105
- 交付物：
  - The Odds API 实测接入
  - API-Football 或商业样例补测
  - 覆盖率、延迟、博彩公司和额度报告
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
- 完成标准：
  - 体彩和亚盘生产 Provider 均明确。
  - 覆盖率、映射准确率、额度、SLA 和授权全部有证据。
  - 未通过时明确 `NO-GO`，不以 Stub 结果代替生产结论。

## 6. M2 比赛标准化与双源映射

### T201 比赛与映射 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V2__init_league_team_match_and_mapping.sql`
  - `V3__init_sporttery_and_asian_odds_snapshots.sql`
  - 对应 Entity/Mapper/Enum
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
- 完成标准：
  - 只给已确认比赛写盘口。
  - 来源、博彩公司、盘口、赔率和时间戳完整。
  - 重复快照幂等。
  - 覆盖率和额度可统计。

## 7. M3 预测发布、锁定和快照

### T301 预测与快照 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V4__init_prediction_and_public_snapshot.sql`
  - Prediction/Snapshot Entity、Mapper 和枚举
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
- 完成标准：
  - 规范化 JSON 可复算。
  - 同一事实生成相同哈希。
  - 文件和数据库哈希一致。
  - 文件写入失败不会标记快照成功。

## 8. M4 赛果与自动结算

### T401 结算与审计 migration

- 状态：`TODO`
- 优先级：P0
- 依赖：T003
- 交付物：
  - `V5__init_settlement_and_audit.sql`
  - `V6__add_core_indexes.sql`
  - Settlement/Audit Entity、Mapper 和枚举
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
- 完成标准：
  - 官方修正不会静默覆盖历史。
  - 新结算可追溯到输入事实和规则版本。
  - 前端能标识“赛果修正后重算”。

## 9. M5 公共 API 与前端

### T501 公共查询 API

- 状态：`IN_PROGRESS`
- 优先级：P0
- 依赖：T202、T303、T404、T004
- 交付物：
  - 首页、比赛列表、详情、历史、统计 Dto/Vo/API
- 完成标准：
  - 分页、筛选和排序白名单生效。
  - 所有已知响应结构显式建模。
  - 历史接口返回全量命中和未命中记录。
  - Controller 与集成测试通过。

当前进度：已完成显式 `MatchSummaryVo` 的每日比赛列表 API 和 Controller 测试；其余公共查询 API 尚未开始。

### T502 前端路由、请求和布局

- 状态：`TODO`
- 优先级：P0
- 依赖：T005
- 交付物：
  - Router
  - QueryClient
  - HTTP Service
  - Public/Admin Layout
- 完成标准：
  - 错误、traceId 和登录失效统一处理。
  - 服务端状态由 TanStack Query 管理。
  - 路由冒烟测试通过。

### T503 比赛列表与详情前后端

- 状态：`IN_PROGRESS`
- 优先级：P0
- 依赖：T501、T502
- 交付物：
  - 比赛列表页
  - 比赛详情页
  - 对应 API Service 和类型
- 完成标准：
  - 体彩让球与亚洲盘明确区分。
  - loading、empty、error、stale 状态完整。
  - 列表筛选和详情跳转测试通过。

当前进度：已完成比赛列表 Demo 的 loading、empty、error 和数据源展示；详情、API Service 抽取、筛选和页面测试尚未完成。

### T504 历史与统计前后端

- 状态：`TODO`
- 优先级：P0
- 依赖：T501、T502
- 交付物：
  - 历史记录页
  - 统计分析页
  - Brier Score、Log Loss 和条件化 ROI 展示
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
- 完成标准：
  - 指标可从事实数据重建。
  - 数据延迟和最后更新时间可见。
  - 移动端窄屏可阅读。

## 10. M6 后台、可观测性与上线

### T601 管理员鉴权

- 状态：`TODO`
- 优先级：P0
- 依赖：T004
- 交付物：
  - Spring Security 配置
  - JWT 登录
  - 管理员角色
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
- 完成标准：
  - 数据源达到 Go 标准。
  - 发布、锁定、快照、结算和历史闭环通过。
  - 连续运行期间无静默丢数。
  - 数据库备份恢复演练通过。
  - 风险提示、免责声明和隐私说明存在。

## 11. 必写测试清单

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

## 12. 推荐的下一步

当前唯一应进入 `IN_PROGRESS` 的任务是 `T002 后端依赖与配置分层`。

推荐顺序：

```text
T001 -> T002 -> T003 -> T004
                    -> T101 -> T103
             T003 -> T102 -> T104
```

T001～T104 完成后，仓库才具备持续实现业务的稳定底座。
