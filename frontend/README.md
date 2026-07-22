# Frontend

React + Vite frontend for JingCai Compass.

## Current scope

Planned MVP pages:

- home
- daily match list
- match detail
- history archive
- statistics dashboard

## Prerequisites

- Node.js 18+
- frontend dependencies installed

## Local run

Install dependencies inside `frontend/` first if needed, then run:

```bash
npm run dev
```

From repository root you can also use:

```bash
npm run frontend:dev
```

## Build

```bash
npm run build
```

Or from repository root:

```bash
npm run frontend:build
```

## Current structure

- `src/app/`: application entry view
- `src/styles/`: global styles
- `src/main.tsx`: bootstrap and router mount

## Suggested next implementation order

1. Introduce route layout and page-level components
2. Split homepage into reusable sections
3. Add list/detail/history/statistics page shells
4. Connect typed API models after backend contracts stabilize
