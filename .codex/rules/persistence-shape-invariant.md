# Persistence Shape Invariant

Read this before touching `CompactionService`, `SessionService`, `ChatService`,
`AgentLoopEngine`, `Message`, or `ContentBlock`.

## Iron Law

The `Message` persisted by `ChatService` and the same logical message in
`AgentLoopEngine` memory must serialize to byte-identical `content_json`.

If the two shapes diverge, `SessionService.updateSessionMessages` compares the
persisted and final message lists with `commonPrefixSize` plus `messageEquals`.
When JSON bytes differ mid-prefix, the suffix is treated as new delta and can be
silently appended again.

## Why It Matters

`messageEquals` serializes `content` with Jackson and compares the JSON string.
A persisted array-shaped user message and an engine-rebuilt string-shaped user
message are different even when their visible text is equivalent.

## Trigger Conditions

- User-message transformers such as reminders, RAG context, metadata envelopes,
  or recovery payloads.
- Compactors that mutate any message content.
- Jackson annotation or field changes on `Message` or `ContentBlock`.
- New callers that persist user messages and then call the agent loop.

## Required Patterns

- Prefer passing the same `Message` object reference from `ChatService` into
  `AgentLoopEngine` after persistence.
- If the engine only has a string input, persistence must also use the simple
  string-shaped `Message.user(text)` form.
- Compactors must not mutate shared message content in place. If content must
  change, create a defensive copy or a replacement `ContentBlock`.
- Any `Message` or `ContentBlock` JSON field/annotation change needs a
  serialize -> deserialize -> serialize roundtrip test proving byte stability.
- Any new user-message path must explicitly choose one of the two safe shapes:
  same object reference, or identical simple string construction on both sides.

## Diagnostic Signal

If logs show mid-prefix divergence with content types such as
`persistContentType=ArrayList` and `engineContentType=String`, treat it as a
persistence-shape regression. The rewrite guard is a fallback, not the design.

## Self-Check

- Does my change break object-reference continuity between persistence and engine memory?
- Can the same logical message serialize differently before and after the loop?
- Does a compactor mutate a shared message object?
- Did I add or change JSON fields without roundtrip coverage?
- Did I add a new caller that bypasses the main reminder-aware path?

If any answer is yes or unknown, add targeted tests and use Full pipeline review.
