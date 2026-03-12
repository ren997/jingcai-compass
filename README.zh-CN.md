# 竞彩罗盘

竞彩罗盘是一个聚焦中国体彩竞彩足球每日比赛的数据分析与概率预测平台。

当前工作名：

- 中文：竞彩罗盘
- 英文：JingCai Compass

当前阶段目标：

- 只覆盖中国体彩每天发布的竞彩足球官方比赛池
- 围绕预测发布、锁定、自动结算、历史透明建立可信闭环
- 先验证一个小而稳的 MVP 是否具备长期产品价值

## 仓库结构

- `docs/`: 产品、技术和实施文档
- `backend/`: Spring Boot 后端
- `frontend/`: React 前端

## 当前文档入口

- MVP 需求文档：`docs/requirements-mvp.md`

## 提交规范

本仓库使用 `commitlint` + `husky` 校验提交信息。

### 本地初始化

```bash
npm install
```

执行后会安装依赖，并注册 `commit-msg` 钩子。

### 提交信息示例

```text
feat(match): 新增竞彩比赛列表接口
docs(mvp): 完善 MVP 需求文档
refactor(prediction): 调整预测发布锁定流程
```
