# 竞彩罗盘

竞彩罗盘是一个聚焦中国体彩竞彩足球每日比赛的数据分析与概率预测平台。

## 当前目标

- 只覆盖中国体彩每天发布的竞彩足球官方比赛池
- 围绕预测发布、锁定、自动结算、历史透明建立可信闭环
- 验证一个小而稳的 MVP 是否具备长期产品价值

## 仓库结构

| 路径 | 说明 |
| --- | --- |
| `docs/` | 产品、技术和实施文档 |
| `backend/` | Spring Boot 后端服务与任务骨架 |
| `frontend/` | React + Vite 前端应用 |
| `.husky/` | 提交钩子 |

## 文档入口

- 文档索引：`docs/README.md`
- MVP 需求文档：`docs/requirements-mvp.md`
- 数据源选型与验证：`docs/data-sources.md`
- 技术方案：`docs/technical-design.md`
- 开发落地手册：`docs/implementation-guide.md`
- 开发任务看板：`docs/dev-tasks.md`

## 本地开发

前置要求：

- Node.js 22 LTS
- Java 21
- Maven 3.9+，且已加入 `PATH`

### 1. 初始化仓库工具

```bash
npm install
```

执行后会安装提交校验依赖，并注册 `commit-msg` 钩子。

### 2. 安装前端依赖

```bash
npm run frontend:install
```

### 3. 启动前端

```bash
npm run frontend:dev
```

### 4. 启动后端

```bash
npm run backend:run
```

该命令会调用 Maven，使用 `backend/pom.xml` 启动 Spring Boot。

## 常用命令

```bash
npm run frontend:install
npm run frontend:dev
npm run frontend:build
npm run backend:run
npm run backend:test
npm run lint:commit
```

## 提交规范

本仓库使用 `commitlint` + `husky` 校验提交信息。

格式：

```text
<type>(<module>): <主题>
```

示例：

```text
feat(match): 新增竞彩比赛列表接口
docs(mvp): 完善 MVP 需求文档
refactor(prediction): 调整预测发布锁定流程
```
