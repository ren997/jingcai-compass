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

Copy `application-local.example.yml` to the Git-ignored `application-local.yml`, fill in the approved cloud development PostgreSQL/Redis credentials, then run from the repository root:

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
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

## Suggested next implementation order

Follow `docs/implementation-guide.md` and `docs/dev-tasks.md` from the repository root.

The cloud PostgreSQL and Redis connection has been verified in T001. The next task is T002: complete dependency and configuration layering. Automated tests use isolated Testcontainers from T003 and must not use the shared cloud database.
