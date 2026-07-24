# 文档索引

这个目录用于沉淀竞彩罗盘的产品范围、技术设计和交付任务，建议按下面顺序阅读。

## 阅读顺序

1. `requirements-mvp.md`

定义 MVP 的产品边界、核心流程、数据实体和验收标准，是当前阶段的基线文档。

2. `data-sources.md`

记录体彩官方数据与亚洲让球盘的候选来源、职责边界、额度风险、比赛映射和两周验证方案。数据源未通过本文的 Go / No-Go 标准前，不进入完整 MVP 开发。

3. `technical-design.md`

固定 MVP 的后端、前端、PostgreSQL、Redis、测试、部署选型，以及模块边界、数据链路、状态机和开发顺序。

4. `business-data-flow.md`

用流程图与 ER 图说明当前已落地表之间的关系，以及体彩 / 亚盘 / 标准化如何落到 `matches` 与标准联赛球队。实现或改表时优先对照本文与 Flyway。

5. `implementation-guide.md`

从当前脚手架开始逐步落地，包含环境、依赖、Flyway、包和类、Provider、接口、测试、部署与验收命令。

6. `dev-tasks.md`

使用 T001～T605 的任务编号维护状态、依赖、交付物和完成标准，是日常开发执行看板。

## 当前状态

- `requirements-mvp.md`：内容最完整，可直接作为后续实现依据
- `data-sources.md`：已完成第一轮候选源调研，等待真实竞彩池样本验证
- `technical-design.md`：技术选型和总体架构已定稿
- `business-data-flow.md`：已按 V1～V4 与 T202/T203 落地情况补充业务流转与表关系
- `implementation-guide.md`：已按当前仓库基线写成可逐步执行的开发手册
- `dev-tasks.md`：编号化执行看板；下一任务以看板顶部「当前活动任务 / 下一任务」为准

## 维护建议

- 产品范围变更优先更新 `requirements-mvp.md`
- 数据源套餐、接口状态和选型结论更新到 `data-sources.md`，并注明核查日期
- 数据模型、接口边界和流程图优先落在 `technical-design.md`
- 表关系与主链路业务流转更新到 `business-data-flow.md`（与 Flyway 保持一致）
- 实现步骤、文件约定和验证命令更新到 `implementation-guide.md`
- 实施顺序、阶段目标和完成情况更新到 `dev-tasks.md`
