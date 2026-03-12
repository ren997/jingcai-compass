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

## Commit Messages

- Use the format `<type>(<module>): <主题>`.
- Keep `type` compatible with Conventional Commits, such as `feat`, `fix`, `refactor`, `docs`, `test`, or `chore`.
- Prefer Chinese for the subject.
- Example: `feat(history): 新增历史预测全量查询接口`
