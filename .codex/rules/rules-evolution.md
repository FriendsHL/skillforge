# Rules Evolution

Read this when updating `.codex` rules, migrating `.claude` strategy, adding
agent-like guidance, or auditing whether new rules are worth their context cost.

## Evolution Heuristics

- New rule: same mistake appears in at least two changes, or a project footgun
  lacks a clear trigger/checklist.
- Update existing rule: a known rule was missed because wording, examples, or
  routing triggers were too weak.
- New specialty checklist: review work has a stable scope and repeatedly takes
  many tool calls or catches issues generic review misses.
- Skip: one-off preference, style-only concern, or guidance already covered by a
  stronger existing rule.

## Migration From Claude To Codex

- Preserve project invariants and verification. Reassess process and triggers
  against current workflow instead of copying them unchanged.
- Convert Claude `TeamCreate`, `SendMessage`, `Write`, and slash-command
  mechanics into capabilities available in the current runtime, optional files,
  and checklist-driven review.
- Do not copy long agent persona text when a concise rule captures the behavior.
- Update `AGENTS.md` progressive disclosure when adding a new `.codex/rules`
  file.
- Run `context-budget.md` audit commands after large rule changes.

Historical source mapping: `../maintenance/claude-strategy-map.md`. Update it
when changing migration coverage; it is provenance, not an execution policy.

## Report Shape

- Rule candidates with trigger paths and source evidence.
- Existing rules to update.
- Low-priority or skipped items with rationale.
- Estimated context cost and what was avoided.
