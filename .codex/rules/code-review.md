# Code Review Rules

Read this when the user asks for a review or when a task requires adversarial
review. Findings come first, ordered by severity, with file/line references.

## Review Process

1. Gather scope with `git diff`, `git diff --staged`, or PR diff metadata.
2. Understand the task intent and changed files.
3. Read full changed files or enough surrounding context to avoid hunk-only
   mistakes.
4. Apply project rules and specialty rules based on touched areas.
5. Report only issues you are at least 80% confident are real.

## Severity

- Blocker: security issue, data loss, invariant violation, compile/runtime error,
  missing explicit requirement, auth bug, or silent failure.
- Warning: maintainability, performance, weak tests, unclear naming, or incomplete
  diagnostics.
- Nit: local style, formatting, docs, or minor naming. Nits should not block.

## SkillForge Cross-Stack Checks

- Backend/Frontend wire shape: verify controller `ResponseEntity.ok(...)` outer
  shape matches frontend `api.get<T>` type and hooks (`r.data` vs
  `r.data.items`).
- Test mocks must mirror the real backend shape, not the frontend author's
  assumption.
- New/changed DTO fields need Java/TS field-name and type alignment plus at
  least one live curl or roundtrip check when practical.
- If core files are touched, confirm Full pipeline or retroactive Full review.
- Feature completion should update delivery docs when the task includes a
  delivery-record expectation; otherwise mention as follow-up.

## Output Shape

- Findings first.
- Then open questions or assumptions.
- Then short summary and verification notes.
- If no issues are found, say so and mention residual risk or test gaps.
