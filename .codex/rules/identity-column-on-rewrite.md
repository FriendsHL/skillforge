# Identity Column On Rewrite

Read this before adding columns to `t_session_message`, changing
`SessionMessageEntity`, `SessionMessageRepository`, or touching
`SessionService.rewriteMessages` / `updateSessionMessages`.

## Iron Law

Any new identity or association column on `t_session_message` must be preserved
through rewrite. `SessionService.rewriteMessages` deletes and reinserts rows, so
fields omitted from `AppendMessage` or patch logic are silently reset to `null`.

## Column Classification

- Identity / association: answers "where did this row come from?" or "which
  higher-level concept does it belong to?". Examples: `trace_id`, future
  `origin_span_id`, future `root_trace_id`. These must be preserved.
- Business content: answers "what is the row now?". Examples: `content_json`,
  `metadata_json`, `msg_type`, `role`. These are supplied by the new messages.
- Audit / counter: examples `created_at`, `updated_at`, `seq_no`. These are
  intentionally regenerated.

## Required Work For New Identity Columns

1. Add a projection query on `SessionMessageRepository` to load non-null old
   values keyed by `seq_no`.
2. Add a `snapshotXBySeqNo(sessionId)` helper in `SessionService`.
3. Patch rewritten messages with the old identity value when the caller did not
   explicitly provide a new non-null value.
4. Extend `AppendMessage` constructors when caller-provided values are needed.
   Keep compatibility constructors defaulting to `null`.
5. Add integration coverage:
   - rewrite preserves old value when caller passes null.
   - caller-provided non-null value wins.
   - no-op when history has no value.
   - tail/query helper behavior if such helper is added.

## Known Limitation

The existing trace-id preservation uses list index alignment. It is exact for
rewrite paths that keep element order and length, but compact rules that remove
messages can shift indexes. Do not invent a new alignment scheme in a narrow
change unless the task explicitly addresses this root limitation.

## Review Requirement

Any change here is Full pipeline work. Review both this file and
`persistence-shape-invariant.md`.
