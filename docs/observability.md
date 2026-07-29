# 可观测性与数据源告警

T603 只提供 Prometheus 采集端点和规则。通知路由由 T604 在部署网络内接入 Alertmanager；应用不会直接请求 Webhook。

## 访问边界

- 主服务端口不提供 Actuator。
- 管理端点默认绑定 `127.0.0.1:8081`，仅暴露 `health`、`info`、`metrics` 和 `prometheus`。
- 容器部署必须保持管理端口不对公网发布；若采集器不在同一主机，T604 应使用私有网络并设置 `MANAGEMENT_BIND_ADDRESS`。
- 生产 `/actuator/health` 不显示数据库、Redis 或配置详情；健康贡献者仍参与状态计算。

## 指标约定

| 指标 | 类型 | 固定标签 | 用途 |
| --- | --- | --- | --- |
| `http.server.requests` | Timer | framework method/uri/status/outcome | API 请求量、状态码和延迟 |
| `jingcai.provider.request` / `jingcai.provider.retry` | Counter | `provider`、`result` | Provider 4xx、429、5xx、超时、网络失败及重试 |
| `jingcai.sync.run` / `jingcai.sync.record` / `jingcai.sync.duration` | Counter/Timer | `provider`、`data_type`、固定状态或种类 | 同步结果、条目量、额度消耗和耗时 |
| `jingcai.mapping.decision` / `jingcai.mapping.pending` | Counter/Gauge | `provider`、固定 `outcome` | 自动映射、复用、待复核和积压 |
| `jingcai.datasource.*` | Gauge | `provider`、`data_type`、固定 `alert` | 比赛池、覆盖率、新鲜度、失败串、额度与告警状态 |
| `jingcai.job.execution` / `jingcai.job.duration` | Counter/Timer | 固定 `job`、`status` | 采集与流水线任务失败、执行量和耗时 |

禁止将 traceId、syncRunId、matchId、predictionId、URL、错误正文、日期或原始 Provider 响应作为指标标签。业务 ID 只出现在受脱敏保护的结构化日志 MDC 中。

## 默认告警条件

- 体彩池最后一次成功同步超过 30 分钟；亚盘超过 40 分钟。
- 成功体彩同步后当天比赛池仍为空。
- 有成功亚盘同步且当天体彩池非空时，亚盘覆盖率低于 90%。
- 同一数据源连续 3 次 `FAILED` 或 `PARTIAL`。
- 当日额度消耗达到 Provider 已配置的正数预警阈值。
- 亚盘 Provider 的待复核映射达到 20。
- 15 分钟内任一受监控定时任务出现失败。

阈值由 `app.observability.*` 与既有 Provider `quota-warning-threshold` 环境变量覆盖。同步任务关闭时，数据源新鲜度、覆盖率、额度和映射告警保持未触发，避免开发环境误报。

## 日志安全

控制台输出 JSON，`traceId`、`jobName`、`syncRunId`、`providerCode`、`matchId` 和 `predictionId` 作为 MDC 字段。共享脱敏器会递归遮蔽密码、API Key、Token、Authorization、Cookie、Secret 等字段，移除 URL 查询参数，并限制原始片段、请求键和错误摘要长度。Provider 和任务错误只记录异常类型与脱敏摘要，不输出原始载荷、请求头或异常堆栈。

规则文件位于 `docs/monitoring/prometheus-rules.yml`。部署时将其加载到 Prometheus；发生告警后先以 `provider`、`alert`、`job` 和 `traceId` 关联指标与日志，再按数据源运行记录定位问题。
