# SkillForge Development Guidelines for Codex

Source: progressive-disclosure index for `.codex/rules`, consolidated from `.claude/rules` on 2026-04-30 and refreshed from `.claude/{rules,agents,commands}` on 2026-07-06.

These are the Codex-local working standards for this repository. Prefer these project-specific rules over generic habits when working inside SkillForge.

## Project Baseline

- SkillForge is a Java 17 + Spring Boot 3.2 + JPA/Hibernate + Maven multi-module backend with a React 19 + TypeScript + Ant Design 6 + Vite + React Router 7 dashboard and a SwiftUI iOS companion generated with XcodeGen.
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
| `.codex/rules/ios.md` | `skillforge-ios/**`, SwiftUI, Xcode project generation, XCTest/XCUITest, simulator, or real-device changes |
| `.codex/rules/ios-pipeline.md` | Any non-Solo native iOS task, iOS multi-agent routing, iOS review, or iOS verification planning |
| `.codex/rules/context-budget.md` | Changing rules/prompts/agents/commands/plugins/MCP exposure, or auditing context overhead |
| `.codex/rules/code-review.md` | User asks for review, or pipeline/reviewer-style assessment is needed |
| `.codex/rules/review-verdict.md` | Producing Mid/Full pipeline reviewer reports or judge summaries |
| `.codex/rules/tdd-workflow.md` | Implementing new behavior or a bug fix where a regression test is practical |
| `.codex/rules/refactor-clean.md` | Explicit dead-code cleanup, duplicate consolidation, or refactor-clean task |
| `.codex/rules/java-build-resolver.md` | Java/Maven/Spring build or compilation failure |
| `.codex/rules/security-review.md` | Auth, authorization, user input, file paths, external URLs, secrets, webhooks, dependency security |
| `.codex/rules/database-review.md` | Flyway migrations, schema/entity changes, JPQL/native SQL, repository queries, database performance |
| `.codex/rules/java-design-review.md` | New Service/Repository/Controller, structural Java refactor, new interface/abstract class, class >500 lines, or explicit design review |
| `.codex/rules/typescript-review.md` | TypeScript/JavaScript review or frontend review checklist work |
| `.codex/rules/performance-review.md` | Performance incident, slow UI/API/query, memory leak, bundle-size or render optimization |
| `.codex/rules/llm-provider-compat.md` | `skillforge-core/llm/**`, provider protocol, SSE, reasoning, tool-call, usage, or model-family changes |
| `.codex/rules/compact-review.md` | Compact subsystem, `CompactionService`, compact recovery, compact strategies, compact integration |
| `.codex/rules/persistence-shape-invariant.md` | `ChatService`/`AgentLoopEngine` message construction, `SessionService` update/rewrite, `Message`/`ContentBlock` shape changes |
| `.codex/rules/identity-column-on-rewrite.md` | Adding/changing `t_session_message` identity/association columns or rewrite preservation |
| `.codex/rules/rules-evolution.md` | Adding/updating Codex rules or migrating Claude strategy into Codex rules |
| `.codex/rules/claude-strategy-map.md` | Auditing how `.claude` rules/agents/commands map into Codex-compatible rules |

## Fast Triage

Use Solo only for:

- Single-line changes.
- Pure comments or docs.
- Config constant adjustments.
- Mechanical rename or file move.
- Pure function edits already locked by strong unit tests.

Use Full when any red light is present:

- Core files: `AgentLoopEngine`, `engine/hook/*`, `core/llm/**`, `CompactionService`, `ChatService`, `SessionService`, `SessionMessageRepository`, Flyway migrations, `ChatWindow.tsx`, `Chat.tsx`, Lifecycle Hooks editor files, or iOS red-light paths from `ios-pipeline.md`.
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
- ChatService-persisted messages and AgentLoopEngine in-memory messages must keep byte-identical JSON shape for the same logical message.
- New `t_session_message` identity/association columns must be preserved by rewrite paths.
- Compact changes must preserve `tool_use`/`tool_result` pairing, summary role, boundaries, identity columns, and surrogate-safe truncation.
- LLM provider changes must be reviewed for cross-provider SSE/tool/reasoning/cache/usage compatibility, not only Java style.

## Non-Negotiables

- Search the local codebase first and reuse existing project patterns.
- Ask or stop when requirements are ambiguous; do not silently choose among multiple valid interpretations.
- Do not clean unrelated dead code while doing another task.
- No fixes before root-cause investigation for technical failures.
- No completion, fixed, or passing claims without fresh verification evidence from the current turn.
- Frontend behavior changes need real browser checks for critical interactions.
- Backend/data changes need API and/or database verification where relevant.
- Do not commit or push unless explicitly requested or approved.
