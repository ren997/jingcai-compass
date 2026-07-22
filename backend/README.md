# Backend

Spring Boot backend for JingCai Compass.

## Current role

The backend will own the MVP closed loop:

- match pool sync
- prediction publishing and lock
- settlement
- history archive
- statistics aggregation

## Tech stack

- Spring Boot 3
- Java 21
- PostgreSQL 16
- Redis
- Flyway
- MyBatis-Plus

## Prerequisites

- Java 21
- Maven 3.9+ available in `PATH`

## Local run

The cloud development PostgreSQL and Redis defaults are configured in `application.yml` and can be overridden with environment variables. Run from the repository root:

```bash
npm run backend:run
```

Or run Maven directly:

```bash
mvn -f backend/pom.xml spring-boot:run
```

## Local test

```bash
npm run backend:test
```

## Product demo API

```text
GET http://localhost:8080/api/public/matches?lotteryDate=2026-07-22
```

The response uses explicit `MatchSummaryVo` models. The default `china` Provider reads the public China Sport Lottery football pool; set `SPORTTERY_PROVIDER=stub` to use clearly labelled synthetic data. PostgreSQL must exist before startup; the application does not create databases.

## Suggested next implementation order

Follow `docs/implementation-guide.md` and `docs/dev-tasks.md` from the repository root.

The cloud PostgreSQL and Redis connection has been verified in T001. The next task is T002: complete dependency and configuration layering. Automated tests use isolated Testcontainers from T003 and must not use the shared cloud database.
