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
- Actuator
- SpringDoc OpenAPI

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

Development defaults can be overridden with environment variables. The main groups are:

- `DB_*`: PostgreSQL connection
- `REDIS_*`: Redis connection
- `SPORTTERY_*`: Provider selection, base URL, timeout and retry settings
- `SYNC_TASKS_ENABLED` and `SPORTTERY_POOL_*`: synchronization task switches and intervals
- `MAX_PAGE_SIZE`: maximum records returned by a MyBatis-Plus page query (default `100`)
- `SPRINGDOC_ENABLED`: OpenAPI/Swagger switch

Copy `application-local.example.yml` to the Git-ignored `application-local.yml` only when local overrides are needed, then run with the `local` profile.

Production must run with the `prod` profile. `application-prod.yml` requires PostgreSQL and Redis credentials from deployment environment variables and disables Swagger by default.

## Local test

```bash
npm run backend:test
```

## Product demo API

```text
GET http://localhost:8080/api/public/matches?lotteryDate=2026-07-22
```

The response uses the common `ApiResponse<T>` envelope with `code`, `message`, `data` and `traceId`; match fields are modeled by `MatchSummaryVo`. The same trace ID is returned in the `X-Trace-Id` response header and written to the server log context. The default `china` Provider reads the public China Sport Lottery football pool; set `SPORTTERY_PROVIDER=stub` to use clearly labelled synthetic data. PostgreSQL must exist before startup; the application does not create databases.

## Development endpoints

With the default development configuration:

```text
GET http://localhost:8080/actuator/health
GET http://localhost:8080/v3/api-docs
GET http://localhost:8080/swagger-ui.html
```

Only `health` and `info` Actuator endpoints are exposed. Production health details are hidden and Swagger remains disabled unless explicitly enabled.

## Suggested next implementation order

Follow `docs/implementation-guide.md` and `docs/dev-tasks.md` from the repository root.

The cloud PostgreSQL and Redis connection has been verified in T001. T002 provides typed configuration, production separation, HTTP timeouts, health checks and OpenAPI. T003 was skipped by project decision because local Docker is not used. T004 provides common API responses, error handling, trace IDs, paging, audit field filling and the minimum security boundary.
