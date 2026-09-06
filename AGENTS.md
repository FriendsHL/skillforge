# SkillForge Development Guidelines for Codex

Java 17 + Spring Boot 3.2 + JPA/Hibernate + Maven backend; React 19 + TypeScript +
Ant Design 6 + Vite + React Router 7 dashboard; SwiftUI iOS app using XcodeGen.
Prefer existing project patterns. These rules are maintained for Codex; historical
Claude migration notes live in `.codex/maintenance/claude-strategy-map.md`.

## Operating Rules

- Inspect local code and relevant requirement docs before implementing.
- Keep scope focused and preserve unrelated working-tree edits. No unrelated cleanup.
- Resolve routine reversible choices autonomously. Ask about material ambiguity
  affecting behavior, scope, compatibility, data, or security; existing authorization
  persists. Planning details live in `think-before-coding.md`.
- Investigate failures before fixing them. Verify changed behavior before claiming
  success, and report missing evidence honestly.
- Critical frontend interactions need real browser checks. Backend/data changes
  need API and/or database evidence where relevant; iOS has explicit test layers.
- Do not commit or push unless the user explicitly requests or approves it.

## Risk And Protected Boundaries

`pipeline.md` owns triage: Solo for low-risk mechanical/docs work; Mid for ordinary
behavior changes; Full for persistence, protocol, security, concurrency, or broad
architecture changes. File names, brief length, and line counts alone do not set
risk. Full requires deeper review, not automatic design approval or fixed agent roles.

Preserve these project invariants and load their owning rules when relevant:

- Persisted and engine messages must have byte-identical JSON shape.
- Rewrites preserve identity/association columns; compact preserves tool pairing,
  summary roles, boundaries, and surrogate-safe truncation.
- Provider streaming must preserve tool identity, reasoning/cache/usage semantics
  and avoid duplicate deltas from retries.
- FE/BE response envelopes and mocks must match; streaming state and subscriptions
  need correct throttling, ownership, and cleanup.

## Progressive Disclosure

Read only files applicable to the task; follow their links only when relevant.
Bare rule filenames refer to `.codex/rules/`.
Language rules own local conventions; specialty rules add checks without replacing
those conventions. Maintenance guidance is needed only when maintaining rules.

| Read | When |
| --- | --- |
| `.codex/rules/docs-reading.md` | Any non-trivial requirement, implementation, or review task |
| `.codex/rules/think-before-coding.md` | Unclear requirements, migration scope, or significant design choices |
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
| `.codex/rules/review-verdict.md` | Optional structured record for complex or multi-reviewer reviews |
| `.codex/rules/tdd-workflow.md` | Implementing new behavior or a bug fix where a regression test is practical |
| `.codex/rules/refactor-clean.md` | Explicit dead-code cleanup, duplicate consolidation, or refactor-clean task |
| `.codex/rules/java-build-resolver.md` | Java/Maven/Spring build or compilation failure |
| `.codex/rules/security-review.md` | Auth, authorization, user input, file paths, external URLs, secrets, webhooks, dependency security |
| `.codex/rules/database-review.md` | Flyway migrations, schema/entity changes, JPQL/native SQL, repository queries, database performance |
| `.codex/rules/java-design-review.md` | New Service/Repository/Controller, structural Java refactor, new cross-module abstraction, or explicit design review |
| `.codex/rules/typescript-review.md` | TypeScript/JavaScript review or frontend review checklist work |
| `.codex/rules/performance-review.md` | Performance incident, slow UI/API/query, memory leak, bundle-size or render optimization |
| `.codex/rules/llm-provider-compat.md` | `skillforge-core/llm/**`, provider protocol, SSE, reasoning, tool-call, usage, or model-family changes |
| `.codex/rules/compact-review.md` | Compact subsystem, `CompactionService`, compact recovery, compact strategies, compact integration |
| `.codex/rules/persistence-shape-invariant.md` | `ChatService`/`AgentLoopEngine` message construction, `SessionService` update/rewrite, `Message`/`ContentBlock` shape changes |
| `.codex/rules/identity-column-on-rewrite.md` | Adding/changing `t_session_message` identity/association columns or rewrite preservation |
| `.codex/rules/rules-evolution.md` | Adding/updating Codex rules or migrating Claude strategy into Codex rules |
