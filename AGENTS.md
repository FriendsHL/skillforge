# SkillForge Development Guidelines for Codex

Source: consolidated from `.claude/rules` on 2026-04-28.

These rules are the Codex-local working version of the SkillForge development standards. Prefer these project-specific rules over generic habits when working inside this repository.

## Project Baseline

- SkillForge is a Java 17 + Spring Boot 3.2 + JPA/Hibernate + Maven multi-module backend with a React 19 + TypeScript + Ant Design 6 + Vite + React Router 7 dashboard.
- Favor existing project patterns over new abstractions.
- Keep work scoped. Do not mix high-risk changes with trivial cleanup in one batch.
- For non-trivial SkillForge development, use a Full review mindset: plan, implement, review, verify, then commit only with user approval.
- Solo-level exceptions: single-line changes, pure comments/docs, config constants, mechanical rename/move, or pure function edits already locked by strong unit tests.

## Requirement Docs Reading Rules

- Before starting any non-trivial requirement, implementation, or review task, read `docs/README.md` first to find the relevant requirement package.
- Read `docs/todo.md` only to confirm the current queue, blocker, priority, and the linked requirement package. Do not treat `todo.md` as the source of detailed requirements.
- For a linked requirement package, open `index.md` first. It is the package entry and should point to the exact MRD / PRD / technical design files needed for the task.
- Before implementation, read `prd.md` and `tech-design.md` for that requirement. Read `mrd.md` only when original user intent, constraints, or unresolved product questions are unclear.
- For Lite requirements, `index.md` is sufficient if it contains user request, acceptance points, implementation notes, and verification.
- Do not read every archived requirement by default. Only open `docs/requirements/archive/<yyyy-MM-dd>-<ID>-<slug>/` when the current requirement links to it, the code area depends on it, or the user explicitly asks.
- Completed delivery facts live in `docs/delivery-index.md`; use it to verify dates, commits, migrations, and shipped scope.
- If `docs/README.md`, `docs/todo.md`, and a requirement package disagree, prefer the requirement package for scope details, prefer `delivery-index.md` for completed facts, and flag the inconsistency before implementing.

## Codex Full Pipeline

This is the Codex translation of `.claude/rules/pipeline.md`.

- SkillForge Full Pipeline means multi-agent collaboration by default. It is not a solo workflow with more caution.
- Codex uses `spawn_agent` instead of Claude `TeamCreate`.
- Use `explorer` agents for bounded codebase investigation and plan inputs.
- Use `worker` agents for implementation slices with explicit file/module ownership.
- Use default/reviewer-style agents for adversarial review and judging when the pipeline needs independent critique.
- For SkillForge Full Pipeline agents, do not override the model unless the user explicitly asks; inherit the parent model.
- The main Codex session orchestrates phases, integrates results, resolves conflicts, runs final verification, and reports the outcome.
- Tell worker agents they are not alone in the codebase, must respect unrelated edits, and must not revert changes made by others.
- Use `git worktree` when isolation is needed: large feature, risky experiment, dirty main workspace, or parallel branch work. Worktree is not a replacement for Full Pipeline review.
- Do not create a worktree for small, single-scope edits where the current workspace is sufficient.

Full Pipeline shape:

1. Phase 0 Research and reuse:
   - Search the local codebase first.
   - Use existing project patterns and proven libraries before writing new utility code.
   - Confirm library/API behavior from primary docs when version-specific behavior matters.
2. Phase 1 Plan, adversarial loop, max 3 rounds:
   - Planner drafts a plan into `/tmp/<task>-plan-r{n}.md`.
   - Reviewer critiques the plan into `/tmp/<task>-plan-review-r{n}.md`.
   - Judge evaluates plan plus review immediately.
   - If not accepted after round 3, stop and ask the user for direction.
   - Skip this phase only when the brief is complete and implementation direction is obvious, such as a pure bug fix or established-pattern extension.
3. Phase 2 Dev, parallel where useful:
   - Backend-only work: one backend worker.
   - Frontend-only work: one frontend worker.
   - Cross-stack work: backend and frontend workers in parallel with disjoint ownership.
   - Tests, docs, and pure config stay attached to the relevant implementation slice unless they are the whole task.
   - Planner/dev prompts must require self-check before final response: reread own output, identify the three most likely issues, fix them or justify why they are acceptable, then return only the cleaned result.
4. Phase 3 Review, adversarial loop, max 3 rounds:
   - Main session collects `git diff HEAD -- ':!docs'` into `/tmp/<task>-diff.patch` when practical.
   - Reviewer agents read the diff first and spot-check full files only when context is needed.
   - Use backend/frontend reviewers according to touched areas.
   - Judge evaluates reviewer reports immediately; do not wait for reviewers to self-certify.
   - Send fixes back to the original dev worker when possible; do not start a fresh dev worker for the same ownership slice.
   - If not accepted after round 3, stop and ask the user for direction.
5. Phase 4 Verify and commit:
   - Main session runs final verification personally.
   - Frontend changes need real browser checks against the running page, not just build success.
   - Critical interactions need DOM/text assertions, not only screenshots.
   - Backend/data changes need API and/or database verification where relevant.
   - Run relevant build/test commands again from the main session.
   - Commit only after user approval.

Review severity:

- `blocker`: data loss, wrong calculation, invariant violation, compile/runtime error, security/auth bug, explicit requirement missing, or silent failure.
- `warning`: performance, readability, maintainability, naming, or thin tests.
- `nit`: style, formatting, docs, or minor naming.
- Reviewer nits go to `/tmp/nits-followup-<task>.md` and do not trigger another fix loop.
- Judge PASS/FAIL decisions consider blockers and warnings, not nits.

## SkillForge Pipeline Triage

In SkillForge, all non-trivial development tasks use Full Pipeline. Only these are Solo:

- Single-line change.
- Pure comments or docs.
- Config constant adjustment.
- Mechanical rename or file move.
- Pure function edit already locked by strong unit tests.

Even if a task sounds small, treat any of the following as definitely Full:

- Touching core files:
  - `skillforge-core/src/main/java/com/skillforge/core/engine/AgentLoopEngine.java`
  - `skillforge-core/src/main/java/com/skillforge/core/engine/hook/*`
  - `skillforge-core/src/main/java/com/skillforge/core/llm/**`
  - `skillforge-core/src/main/java/com/skillforge/core/context/CompactionService.java`
  - `skillforge-server/src/main/java/com/skillforge/server/service/ChatService.java`
  - `skillforge-server/src/main/java/com/skillforge/server/service/SessionService.java`
  - `SessionMessageRepository`
  - `skillforge-server/src/main/resources/db/migration/V*.sql`
  - `skillforge-dashboard/src/components/ChatWindow.tsx`
  - `skillforge-dashboard/src/pages/Chat.tsx`
  - `skillforge-dashboard/src/components/LifecycleHooksEditor.tsx`
  - `skillforge-dashboard/src/hooks/useLifecycleHooks.ts`
  - `skillforge-dashboard/src/constants/lifecycleHooks.ts`
- Multi-invariant paired protocols: `tool_use`/`tool_result`, lock/unlock, transaction begin/commit, request/response, lease/heartbeat.
- New persistence entity, schema migration, JPQL/native SQL change, serializer/deserializer config, time/date serialization, or build plugin change.
- Cross-module feature work, new REST endpoint, or broad brief.
- Changes touching known footguns below.

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

## Common Engineering Rules

- Prefer KISS, DRY, and YAGNI in that order. Add abstractions only after real duplication or complexity appears.
- Keep data immutable where practical; avoid hidden mutation and side effects.
- Validate all user input and external data at system boundaries. Use schema or Bean Validation when available.
- Handle errors explicitly. Never silently swallow errors. UI-facing errors should be usable; server logs should keep diagnostic context without leaking secrets.
- Keep functions focused, normally under 50 lines. Keep files cohesive, with 800 lines as a hard warning threshold.
- Avoid deep nesting; prefer guard clauses and early returns.
- Use named constants for meaningful thresholds, timeouts, sizes, and limits.
- No hardcoded secrets, tokens, passwords, or API keys. Use env vars/config/secret managers.
- Do not leak stack traces, SQL errors, internal paths, API keys, tokens, or session content to clients or logs.
- Use parameterized SQL/JPQL/native queries. Never concatenate user input into SQL.

## Testing And Review

- Default to TDD for new behavior: write failing test, implement, refactor, verify.
- Target 80%+ meaningful coverage for changed behavior, with unit, integration, and E2E coverage scaled to risk.
- Use Arrange-Act-Assert structure and behavior-focused test names.
- Before completion, run the relevant build/test commands and report exactly what passed or could not be run.
- Review security first, then correctness, then maintainability/performance.
- Block on critical security, data loss, invariant violation, compile/runtime errors, missing explicit requirements, and silent failure.
- Treat nits as follow-up unless they affect correctness or clarity.

## Java Rules

- Use constructor injection. Never use field injection with `@Autowired`.
- JPA entities need a no-arg constructor. Tables follow the `t_` prefix convention.
- Use `@EntityListeners(AuditingEntityListener.class)` with `@CreatedDate` and `@LastModifiedDate` for audit fields.
- Store enum/status values as strings with bounded column length.
- Use `@Column(columnDefinition = "TEXT")` or `CLOB` for large text fields to avoid 255-character truncation.
- Put `@Transactional(readOnly = true)` on read Service methods and `@Transactional` on multi-repository writes.
- Tool implementations use `com.skillforge.core.skill.Tool`; names are globally unique PascalCase and descriptions must be useful to the LLM.
- Register Java tools in `SkillForgeConfig`; `SystemSkillLoader` is for zip package skills, not Java tools.
- New LLM providers implement `com.skillforge.core.llm.LlmProvider` and register in `LlmProviderFactory`.
- Java naming:
  - Classes/interfaces: `PascalCase`
  - Methods/fields/locals: `camelCase`
  - Constants: `SCREAMING_SNAKE_CASE`
  - Packages: lowercase `com.skillforge.<module>.<layer>`
  - Entities: `*Entity`
  - Repositories: `*Repository`
  - Request/response DTOs: `*Request`, `*Response`
- Prefer records for DTOs/value types where compatible with project conventions.
- Repository finder methods return `Optional<T>`. Use `orElseThrow`, `map`, or `flatMap`; do not use `Optional` as a field or parameter.
- Prefer unchecked, domain-specific exceptions and centralized controller/WebSocket error handling.
- Use SLF4J loggers. Avoid broad `catch (Exception)` except at top-level boundaries.
- Java tests use JUnit 5, Mockito, AssertJ, and Testcontainers where real integration services are needed. Mirror `src/main/java` package structure in `src/test/java`.
- Java test methods use `methodName_scenario_expectedBehavior()` plus `@DisplayName` when useful.

## Frontend And TypeScript Rules

- New API functions must not use `data: any`; define request/response interfaces.
- Prefer `interface` for object shapes and props; use `type` for unions, intersections, tuples, mapped types, and utility types.
- Public/exported functions and shared utilities should have explicit parameter and return types.
- Avoid `any`; use `unknown` at untrusted boundaries and narrow safely.
- Do not use `@ts-ignore`. If unavoidable, use `@ts-expect-error` with a reason.
- Avoid double assertions like `as unknown as X` unless there is a documented reason.
- Component files use `PascalCase.tsx`; utilities/hooks use `camelCase.ts`.
- One main exported component per component file. Small private subcomponents may live in the same file.
- Extract complex logic into custom hooks with `use` prefix. Keep JSX readable.
- Use `React.memo` for pure presentation components where parent churn matters.
- Local state uses `useState`/`useReducer`; shared navigation state should use context or URL params, not localStorage hacks.
- API calls live in `src/api/index.ts`; components should not call `axios` directly.
- Every API flow handles loading and error states. Fetch independent data with `Promise.all`.
- Use Ant Design forms, modals, messages, and table loading props consistently.
- Do not override `.ant-*` internals directly; use theme tokens/config where possible.
- Prefer CSS variables already present in the project. Static styles go in CSS/CSS Modules; inline styles only for dynamic computed values.
- Do not use `!important`.
- Use `useCallback`/`useMemo` only for child callbacks or expensive derived values.
- Long lists over roughly 100 rows need virtualization.
- Dispose ECharts instances on unmount.
- No stray `console.log` in committed code.

## Dashboard Design Rules

- SkillForge dashboard should feel precise, professional, and developer-oriented, with inspiration from Linear, Raycast, Cursor, Vercel, Posthog, and Sentry.
- Avoid template-looking UI: generic card grids, centered generic hero + gradient, raw Ant Design defaults, flat layouts with no hierarchy, one-size radius/shadow, and default dashboard layouts without a design point of view.
- Meaningful UI surfaces should show several of: scale hierarchy, intentional spacing rhythm, depth/layering, deliberate typography, semantic color, designed interaction states, clarifying motion, and integrated data visualization.
- Preferred visual direction: dark precision with subtle depth, 8px spacing grid, small elements around 4px radius, cards around 8px, large containers around 12px, and layered shadows.
- Use CSS variables/tokens for palette, typography, spacing, timing, and easing.
- Use semantic HTML first. Avoid wrapper `div` stacks when a semantic element fits.
- Animate compositor-friendly properties such as `transform`, `opacity`, `clip-path`, and sparing `filter`. Avoid animating layout-bound properties.
- Web UI verification should include meaningful screenshots at key breakpoints, accessibility/keyboard checks, reduced-motion behavior, contrast, overflow, and deterministic E2E assertions.

## Web Security And Performance

- Configure production CSP; prefer nonce-based scripts over `unsafe-inline`.
- Never inject unsanitized HTML. Avoid `innerHTML`/`dangerouslySetInnerHTML` unless sanitized with a vetted sanitizer.
- Use SRI for CDN scripts and prefer self-hosting critical dependencies where practical.
- State-changing forms need CSRF protection, rate limiting, and client/server validation.
- Target Core Web Vitals: LCP < 2.5s, INP < 200ms, CLS < 0.1, FCP < 1.5s, TBT < 200ms.
- Set explicit image dimensions. Hero media may be eager/high priority; below-fold media should be lazy.
- Prefer AVIF/WebP with fallbacks and avoid shipping images far beyond rendered size.
- Use at most two font families unless there is a clear reason.
- Dynamically import heavy libraries and defer non-critical CSS/JS.

## Git And PR Rules

- Commit messages use conventional types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`.
- Before PR: inspect full branch diff, not just the latest commit; include a useful summary and test plan.
- Do not commit or push unless the user asks or has clearly approved.
- Before requesting review, ensure checks pass, conflicts are resolved, and the branch is up to date when practical.
