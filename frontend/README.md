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

- Node.js 22 LTS
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

The Vite development server proxies `/api` to `http://localhost:8080`. Start the backend with the `local` profile first, then open `http://localhost:5173` to view the daily match Demo.

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

Follow `docs/implementation-guide.md` and `docs/dev-tasks.md` from the repository root.

Frontend foundation starts at T005. Business pages start after the public API contracts in T501 are explicit; the first vertical page flow is T503 match list and detail.
