# 预测、结算与快照告警响应

本说明对应 `prometheus-rules.yml` 的预测生命周期规则。指标不携带比赛、预测、快照或追踪号等无界标签；这些标识仅存在于 JSON 结构化日志中。

## 预测锁定滞后

`JingcaiPredictionLockOverdue` 表示存在 `PUBLISHED` 预测在锁定时间后超过配置宽限期仍未进入 `LOCKED`。先查看 `jingcai_prediction_lock_overdue`、`jingcai_job_execution_total{job="prediction_lock"}`，再用 `traceId`、`jobName=prediction_lock` 和 `predictionId` 查询日志。确认任务开关、数据库时间和单条锁定异常后等待重试；不得直接修改预测状态或核心预测字段。

## 结算积压超时

`JingcaiSettlementBacklogOverdue` 表示当前 `FINAL`/`VOID` 事实已超过宽限期，且锁定预测至少有一个 HAD/HHAD 结算缺失或仍引用旧事实。先检查 `jingcai_settlement_backlog_predictions`、`jingcai_settlement_item_total` 与 `jingcai_job_execution_total{job="settlement"}`，随后按日志的 `predictionId` 追溯事实和结算版本链。修复任务或事实输入后由自动结算/重算追加新版本；不得直接编辑既有结算记录。

## 快照发布与完整性异常

`JingcaiPredictionSnapshotPublishFailed` 与 `JingcaiPredictionSnapshotHashMismatch` 在最近十分钟内有失败或校验不一致时立即触发。检查 `jingcai_snapshot_publish_total`、`jingcai_snapshot_integrity_failure_total` 及 `jobName=snapshot_publish` 的日志；日志中的 `snapshotId` 用于关联元数据，不记录对象路径、原始预测内容或失败正文。恢复存储与发布流程后重新运行任务；不得手工把 `FAILED`/`PENDING` 快照改为 `PUBLISHED`。

`jingcai_lifecycle_alert_active{component="snapshot"}` 反映当前状态型异常，下一次成功发布或复用会清除它；Counter 保留历史事件，Prometheus 规则仍按近期增量触发告警。
