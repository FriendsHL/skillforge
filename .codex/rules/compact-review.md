# Compact Review Rules

Read this for changes to `skillforge-core/src/main/java/com/skillforge/core/compact/**`,
`CompactionService`, `SessionService` rewrite/update paths, compact recovery
code, and `AgentLoopEngine` compact integration.

## Compact Invariants

1. `tool_use` and `tool_result` pairing must remain intact. Removing an
   assistant tool call or its matching user result can make the next LLM request
   invalid.
2. Full-compact summary rows must use role `USER`. A `SYSTEM` summary row can
   roundtrip into a different logical message shape and trigger rewrite drift.
3. `COMPACT_BOUNDARY` rows must be preserved by rewrite and reload paths.
4. Persistence and engine message JSON must remain byte-identical. Read
   `persistence-shape-invariant.md`.
5. Rewrite must preserve identity columns. Read `identity-column-on-rewrite.md`.
6. Compactors must not mutate shared `Message` / `ContentBlock` objects in place.
7. Full compact has four important trigger paths and tests should cover the
   ones touched by the change:
   - REST or engine hard compact.
   - engine preemptive compact.
   - post-overflow recovery from `LlmContextLengthExceededException`.
   - session-memory compact path.
8. String truncation must be UTF-16 surrogate-safe. Do not split emoji or other
   non-BMP characters.

## Review Checklist

- Search for `tool_use_id`, `tool_call_id`, `working.remove`, and any list
  filtering/removal logic.
- Check `MSG_TYPE_SUMMARY` creation and boundary preservation.
- Check whether the implementation creates replacement objects instead of
  mutating shared content.
- Check tests include pairing, boundary, roundtrip, and source-path coverage
  relevant to the change.
- Confirm no compact rule creates hidden provider-specific assumptions.

## Severity

- Blocker: any invariant violation, data loss, invalid pairing, missing boundary,
  compile/runtime error, or silent failure.
- Warning: thin coverage, performance risk, unclear source attribution.
- Nit: naming, local formatting, or comment clarity.
