# Optional Review Record

Use for complex reviews or multi-reviewer integration when a structured record
helps. Ordinary reviews use the concise output in `code-review.md`.

- **Scope and acceptance:** what was checked, with evidence for missing behavior.
- **Findings:** blockers, warnings, then nits; include file/line references and
  concrete impact. Check correctness/security even when requirements are unmet.
- **Verification:** commands/results, inspected paths, and unverified boundaries.
- **Decision:** proceed, fix, or blocked, with the reason. Missing requested
  behavior and scope creep are blockers; style nits do not block delivery.

For follow-up reviews, mark prior findings fixed, unresolved, or partial with
fresh evidence, then check new changes and affected paths. Do not move goalposts
with unrelated nits.

When combining reviewers, consolidate duplicate findings and resolve conflicting
claims against code and evidence. Separate self-review from independent review;
no separate judge persona or temporary file is required.
