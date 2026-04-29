# Common Engineering Rules

Read this for all implementation tasks.

## General Rules

- Prefer KISS, DRY, and YAGNI in that order. Add abstractions only after real duplication or complexity appears.
- Keep data immutable where practical; avoid hidden mutation and side effects.
- Validate all user input and external data at system boundaries. Use schema or Bean Validation when available.
- Handle errors explicitly. Never silently swallow errors.
- UI-facing errors should be usable; server logs should keep diagnostic context without leaking secrets.
- Keep functions focused, normally under 50 lines. Keep files cohesive, with 800 lines as a hard warning threshold.
- Avoid deep nesting; prefer guard clauses and early returns.
- Use named constants for meaningful thresholds, timeouts, sizes, and limits.
- No hardcoded secrets, tokens, passwords, or API keys. Use env vars, config, or secret managers.
- Do not leak stack traces, SQL errors, internal paths, API keys, tokens, or session content to clients or logs.
- Use parameterized SQL, JPQL, and native queries. Never concatenate user input into SQL.

## Testing And Review

- Default to TDD for new behavior: write failing test, implement, refactor, verify.
- Target 80%+ meaningful coverage for changed behavior, with unit, integration, and E2E coverage scaled to risk.
- Use Arrange-Act-Assert structure and behavior-focused test names.
- Before completion, run the relevant build/test commands and report exactly what passed or could not be run.
- Review security first, then correctness, then maintainability/performance.
- Block on critical security, data loss, invariant violation, compile/runtime errors, missing explicit requirements, and silent failure.
- Treat nits as follow-up unless they affect correctness or clarity.

## Git And PR Rules

- Commit messages use conventional types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`.
- Before PR: inspect full branch diff, not just the latest commit; include a useful summary and test plan.
- Do not commit or push unless the user asks or has clearly approved.
- Before requesting review, ensure checks pass, conflicts are resolved, and the branch is up to date when practical.
