# Repository Conventions

## Naming

- Request and input parameter objects use the `Dto` suffix.
- Response and outward-facing view objects use the `Vo` suffix.
- Enum names should reflect sportsbook-independent business meaning, such as `MatchStatus`, `MarketType`, and `SettlementStatus`.

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
