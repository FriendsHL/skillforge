# Context Budget

Audit when adding prompt/rule/tool exposure or investigating slower or less
reliable sessions. No calendar-based audit is required.

- Keep AGENTS as an index and compact operating rules. Read specialty files only
  on their triggers; total repository line count is not per-turn context usage.
- Keep each policy in one owning file; other files link to it. Move historical
  migration notes to `.codex/maintenance/` and avoid repeated agent personas.
- Keep tool exposure and agent descriptions focused. Measure actual loaded text
  and schemas before claiming the largest cost or a performance improvement.
- Prefer executable checks for deterministic conventions and targeted regression
  tests for invariants; retain the short rationale and trigger in rules.

After significant edits:

```bash
rg --files .codex/rules .codex/maintenance
wc -l AGENTS.md .codex/rules/*.md
git diff --check -- AGENTS.md .codex
```

Inspect the diff, verify AGENTS covers each rule with a precise trigger, and check
references after moves. Compare representative task behavior before attributing
quality or latency gains to fewer lines.
