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

数据库和 Redis 已在 `application.yml` 中提供开发默认值，可通过环境变量覆盖。直接执行：

```bash
npm run backend:run
```

该命令会调用 Maven，使用 `backend/pom.xml` 启动 Spring Boot。

### 5. 产品 Demo

后端启动后可访问：

```text
GET http://localhost:8080/api/public/matches?lotteryDate=2026-07-22
```

前端开发服务器会将 `/api` 代理到后端。打开 `http://localhost:5173` 可查看按日期筛选的竞彩比赛卡片。

当前默认通过独立 Provider 读取中国体彩网公开前台的竞彩足球比赛池；可设置 `SPORTTERY_PROVIDER=stub` 切换到明确标记的虚构演示数据。公开前台接口仍需继续验证稳定性和使用许可，不能直接视为生产级开放 API。应用不会自动创建 PostgreSQL 数据库，目标数据库需在启动前存在；库内结构后续统一由 Flyway 管理。

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
