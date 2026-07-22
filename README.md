# JingCai Compass

JingCai Compass is an analysis platform for China Sports Lottery daily football matches.

Chinese README: `README.zh-CN.md`

## Product focus

- Cover only the official daily football match pool published by China Sports Lottery
- Build a transparent workflow around prediction publishing, locking, settlement, and history
- Validate whether a small but trustworthy MVP has long-term value

## Repository map

| Path | Purpose |
| --- | --- |
| `docs/` | Product, technical, and delivery documents |
| `backend/` | Spring Boot API and scheduled task skeleton |
| `frontend/` | React + Vite web application |
| `.husky/` | Commit hooks |

## Document entry points

- Document index: `docs/README.md`
- MVP requirements: `docs/requirements-mvp.md`
- Technical design draft: `docs/technical-design.md`
- Delivery tasks: `docs/dev-tasks.md`

## Local development

Prerequisites:

- Node.js 18+
- Java 21
- Maven 3.9+ available in `PATH`

### 1. Initialize repository tools

```bash
npm install
```

This installs commit tooling and registers the `commit-msg` hook.

### 2. Install frontend dependencies

```bash
npm run frontend:install
```

### 3. Start the frontend

```bash
npm run frontend:dev
```

### 4. Start the backend

```bash
npm run backend:run
```

This command delegates to Maven and starts the Spring Boot service from `backend/pom.xml`.

## Common scripts

```bash
npm run frontend:install
npm run frontend:dev
npm run frontend:build
npm run backend:run
npm run backend:test
npm run lint:commit
```

## Commit convention

This repository uses `commitlint` with the Conventional Commits preset.

Format:

```text
<type>(<module>): <主题>
```

Examples:

```text
feat(match): 新增竞彩比赛列表接口
docs(mvp): 完善 MVP 需求文档
refactor(prediction): 调整预测发布锁定流程
```
