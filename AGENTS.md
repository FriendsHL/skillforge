# SkillForge Development Guidelines for Codex

Source: progressive-disclosure index for `.codex/rules`, consolidated from `.claude/rules` on 2026-04-30.

These are the Codex-local working standards for this repository. Prefer these project-specific rules over generic habits when working inside SkillForge.

## Project Baseline

- SkillForge is a Java 17 + Spring Boot 3.2 + JPA/Hibernate + Maven multi-module backend with a React 19 + TypeScript + Ant Design 6 + Vite + React Router 7 dashboard.
- Favor existing project patterns over new abstractions.
- Keep work scoped. Do not mix high-risk changes with trivial cleanup in one batch.
- Use SkillForge pipeline triage: Solo only for narrow exceptions, Mid by default, Full for red-light work.
- Commit or push only after the user clearly asks or approves.

## Progressive Disclosure

`AGENTS.md` is the entrypoint. Read only the `.codex/rules/*` files that apply to the current task.

| Read | When |
| --- | --- |
| `.codex/rules/docs-reading.md` | Any non-trivial requirement, implementation, or review task |
| `.codex/rules/think-before-coding.md` | Any non-trivial task, ambiguous task, or major design decision |
| `.codex/rules/pipeline.md` | Any non-Solo development task or any task needing agent review |
| `.codex/rules/systematic-debugging.md` | Any bug, failing test, performance issue, build failure, production incident, or integration problem |
| `.codex/rules/verification-before-completion.md` | Before claiming work is complete, fixed, passing, or ready |
| `.codex/rules/common-engineering.md` | Any implementation task |
| `.codex/rules/java.md` | Java, JPA, repositories, services, migrations, tools, or LLM provider changes |
| `.codex/rules/frontend.md` | TypeScript, React, frontend API calls, hooks, WebSocket UI, or dashboard state changes |
| `.codex/rules/design.md` | Dashboard layout, visual design, CSS, accessibility, or frontend UX changes |
| `.codex/rules/context-budget.md` | Changing rules/prompts/agents/commands/plugins/MCP exposure, or auditing context overhead |

## Fast Triage

Use Solo only for:

- Single-line changes.
- Pure comments or docs.
- Config constant adjustments.
- Mechanical rename or file move.
- Pure function edits already locked by strong unit tests.

Use Full when any red light is present:

- Core files: `AgentLoopEngine`, `engine/hook/*`, `core/llm/**`, `CompactionService`, `ChatService`, `SessionService`, `SessionMessageRepository`, Flyway migrations, `ChatWindow.tsx`, `Chat.tsx`, or Lifecycle Hooks editor files.
- Multi-invariant paired protocols: `tool_use`/`tool_result`, lock/unlock, transaction begin/commit, request/response, lease/heartbeat.
- New persistence entity, schema migration, or JPQL/native SQL change.
- Cross-3-module feature work, a new REST endpoint plus frontend plus tests, or a broad brief over roughly 800 words.
- Changes touching known footguns.

Use Mid for non-Solo tasks that do not hit Full red lights.

Mixed batches inherit the highest-risk item. Do not hide a Full-level change inside a Solo batch.

## Known Footguns

- Jackson `ObjectMapper` must register `JavaTimeModule` and disable timestamp date output. Prefer the Spring-managed `ObjectMapper` bean over `new ObjectMapper()`.
- New persisted time fields use `Instant`. `LocalDateTime` is legacy and should not spread.
- `@Transactional` belongs on public Service-layer methods. It does not work on private methods through Spring AOP.
- LLM provider `chat()` may retry `SocketTimeoutException`; `chatStream()` must not retry because streamed deltas may be duplicated.
- Frontend WebSocket subscriptions must close in `useEffect` cleanup.
- Streaming UI updates must be throttled; do not `setState` for every delta.
- `useImperativeHandle` refs do not trigger parent re-render. Use state for UI state, refs only for save-time snapshots.
- Jackson discriminated unions using `@JsonTypeInfo` and `@JsonSubTypes` need no-arg constructors and should tolerate unknown properties for compatibility.

## Non-Negotiables

- Search the local codebase first and reuse existing project patterns.
- Ask or stop when requirements are ambiguous; do not silently choose among multiple valid interpretations.
- Do not clean unrelated dead code while doing another task.
- No fixes before root-cause investigation for technical failures.
- No completion, fixed, or passing claims without fresh verification evidence from the current turn.
- Frontend behavior changes need real browser checks for critical interactions.
- Backend/data changes need API and/or database verification where relevant.
- Do not commit or push unless explicitly requested or approved.
