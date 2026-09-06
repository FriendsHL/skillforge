# Common Engineering Rules

Read for implementation tasks. AGENTS owns scope and authorization boundaries;
language rules own local conventions; specialty rules own domain checks.

- Reuse local patterns and add abstractions only for demonstrated complexity or
  meaningful duplication. Keep responsibilities cohesive; line counts alone are
  not a reason to split code or block a change.
- Validate untrusted input at boundaries. Handle errors explicitly with usable
  client messages and redacted diagnostic context. Never hardcode secrets or
  concatenate user input into SQL; apply `security-review.md` on its triggers.
- Avoid hidden mutation, especially shared state. Remove only orphaned code caused
  by this task; explicit cleanup follows `refactor-clean.md`.
- For behavior changes, cover relevant success, failure, and boundary cases at
  the smallest layer that proves them. Prefer a failing regression test when
  practical (`tdd-workflow.md`); do not impose a universal coverage percentage or
  write tests that merely mirror implementation.
- Build failures follow `systematic-debugging.md` and, for Java, the focused
  `java-build-resolver.md` checklist.
- Verification and evidence follow `verification-before-completion.md`; review
  severity follows `code-review.md`.
- When authorized to commit, use conventional types: `feat`, `fix`, `refactor`,
  `docs`, `test`, `chore`, `perf`, `ci`. Before PR review, inspect the full branch
  diff against its actual base and describe the behavior and relevant validation.
