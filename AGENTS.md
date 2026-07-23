# Repository Conventions

## Naming

- Request and input parameter objects use the `Dto` suffix.
- Response and outward-facing view objects use the `Vo` suffix.
- Business enums use the `*Enum` suffix and sportsbook-independent names, such as `MatchStatusEnum`, `MarketTypeEnum`, and `SettlementStatusEnum`.

## Package Layout

- Organize by business module first (`match`, `odds`, `system`, ...), then by technical role inside each module:
  `controller`, `dto`, `vo`, `entity`, `enums`, `mapper`, `service`, `client`, `job`, `exception`.
- Controllers depend on `XxxService` interfaces; implementations are named `XxxServiceImpl` and live in the same `service` package.
- External provider adapters, HTTP clients and provider properties live in `client`.
- Do not introduce new hexagonal layer packages such as `api`, `application`, `domain`, or `infrastructure` for business modules.
- `system` holds cross-cutting concerns (`api` envelopes, `config`, `exception`, shared `provider` error types). Filter/audit helpers may remain under `system.infrastructure` until relocated.

## API Modeling

- Do not expose raw JSON structures when the response shape is known.
- Prefer explicit Java models for request and response payloads.
- Add brief field comments on public request and response models when the meaning is not obvious.

## Domain Rules

- Core prediction fields become immutable after the match enters the locked state.
- Settlement results must be derived from final match data, not edited directly.
- Audit records should append changes instead of overwriting history.

## Task Tracking

- Use `docs/dev-tasks.md` as the single execution board for development order and status.
- Before changing product code, set exactly one task to `IN_PROGRESS`, update the current active task, and record the intended scope.
- During development, check off completed task steps in order.
- Before finishing an increment, run the task validation commands and record the result.
- Finish by setting the task to `DONE`, `PARTIAL`, or `BLOCKED`, updating the next task and milestone status, and committing the task-board update together with the code.
- Do not mark a task `DONE` when only a prototype or partial vertical slice exists.

## Commit Messages

- Use the format `<type>(<module>): <主题>`.
- Keep `type` compatible with Conventional Commits, such as `feat`, `fix`, `refactor`, `docs`, `test`, or `chore`.
- Prefer Chinese for the subject.
- Example: `feat(history): 新增历史预测全量查询接口`
