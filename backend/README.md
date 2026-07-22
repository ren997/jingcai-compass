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
- PostgreSQL
- Redis
- Flyway
- MyBatis-Plus

## Prerequisites

- Java 21
- Maven 3.9+ available in `PATH`

## Local run

From repository root:

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

## Suggested next implementation order

1. Base package structure for controller, application, domain, infrastructure
2. Match sync task skeleton
3. Prediction aggregate and immutable-field rules
4. Settlement flow and audit append-only records
