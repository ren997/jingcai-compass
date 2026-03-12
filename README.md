# JingCai Compass

JingCai Compass is a data analysis platform focused on China Sports Lottery football matches.

Chinese README: `README.zh-CN.md`

Working product name:

- Chinese: 竞彩罗盘
- English: JingCai Compass

Current goal:

- Focus only on the daily official football match pool published by China Sports Lottery
- Build a transparent analysis workflow around publishing, locking, settlement, and historical verification
- Validate whether a small but trustworthy MVP has real long-term product value

## Repository layout

- `docs/`: product, technical, and implementation documents
- `backend/`: Spring Boot backend
- `frontend/`: React frontend

## Current document entry

- MVP requirements: `docs/requirements-mvp.md`

## Commit message linting

This repository uses `commitlint` with the Conventional Commits preset.

### Local setup

```bash
npm install
```

After installation, Husky will register the `commit-msg` hook automatically.

### Commit message examples

```text
feat(match): 新增竞彩比赛列表接口
docs(mvp): 完善 MVP 需求文档
refactor(prediction): 调整预测发布锁定流程
```
