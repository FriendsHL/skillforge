# Codex Pipeline

This is the Codex-local translation of `.claude/rules/pipeline.md`.

## Tool Mapping

- Codex uses `spawn_agent` instead of Claude `TeamCreate` / `SendMessage`.
- Use `explorer` agents for bounded codebase investigation and plan inputs.
- Use `worker` agents for implementation slices with explicit file/module ownership.
- Use default/reviewer-style agents for adversarial review and judging when the pipeline needs independent critique.
- Do not override the model unless the user explicitly asks; inherit the parent model.
- The main Codex session orchestrates phases, integrates results, resolves conflicts, runs final verification, and reports the outcome.
- Tell worker agents they are not alone in the codebase, must respect unrelated edits, and must not revert changes made by others.
- Use `git worktree` when isolation is needed: large feature, risky experiment, dirty main workspace, or parallel branch work. Worktree is not a replacement for review.
- Do not create a worktree for small, single-scope edits where the current workspace is sufficient.

## Pipeline Triage

In SkillForge, docs-only and other narrow exceptions are Solo, red-light work is Full, and everything else defaults to Mid.

Use Solo only for:

- Single-line change.
- Pure comments or docs.
- Config constant adjustment.
- Mechanical rename or file move.
- Pure function edit already locked by strong unit tests.

Use Full when any red light is present:

- Touching core files:
  - `skillforge-core/src/main/java/com/skillforge/core/engine/AgentLoopEngine.java`
  - `skillforge-core/src/main/java/com/skillforge/core/engine/hook/*`
  - `skillforge-core/src/main/java/com/skillforge/core/llm/**`
  - `skillforge-core/src/main/java/com/skillforge/core/compact/*` (compaction algorithm layer: `ContextCompactorCallback`, Light/Full/SessionMemory strategies, `TokenEstimator`, `CompactableToolRegistry`, boundary detection)
  - `skillforge-server/src/main/java/com/skillforge/server/service/CompactionService.java` (orchestration layer: stripe lock, 3-phase split, in-flight dedup, persistence, broadcast)
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
- New persistence entity, schema migration, or JPQL/native SQL change.
- Cross-3-module feature work, a new REST endpoint plus frontend plus tests, or a broad brief over roughly 800 words.
- Changes touching known footguns.

Use Mid for non-Solo tasks that do not hit Full red lights, including ordinary bug fixes, visible UI behavior changes, field additions without schema changes, new REST endpoints without schema changes, serializer/deserializer config, build plugin changes, new dependencies, single- or dual-module features, and changed test assertions.

Mixed batches inherit the highest-risk item. Do not hide a Full-level change inside a Solo batch.

## Mid Pipeline Shape

1. Phase 0 Research and reuse:
   - Search the local codebase first.
   - Use existing project patterns and proven libraries before writing new utility code.
2. Phase 1 Dev:
   - Backend-only work: one backend worker.
   - Frontend-only work: one frontend worker.
   - Cross-stack work: backend and frontend workers in parallel with disjoint ownership.
   - Tests, docs, and pure config stay attached to the relevant implementation slice unless they are the whole task.
3. Phase 2 Review, one adversarial round:
   - Main session collects `git diff HEAD -- ':!docs'` into `/tmp/<task>-diff.patch` when practical.
   - Reviewer agents read the diff first and spot-check full files only when context is needed.
   - Judge evaluates reviewer reports immediately.
   - If there are no blockers, proceed to final verification.
   - If there are warnings that can be fixed once, send fixes back to the original dev worker, then the main session spot-checks before final verification.
   - If there is a blocker or architecture-direction issue, upgrade to Full or stop for user direction.
4. Phase Final Verify and commit:
   - Main session runs final verification personally.
   - Commit only after user approval.

## Full Pipeline Shape

1. Phase 0 Research and reuse:
   - Search the local codebase first.
   - Use existing project patterns and proven libraries before writing new utility code.
   - Confirm library/API behavior from primary docs when version-specific behavior matters.
2. Phase 1 Plan, optional adversarial loop, max 2 rounds:
   - Plan phase is skipped by default for complete briefs, ordinary bug fixes, established-pattern extensions, and most routine tasks.
   - Plan phase is required when there are multiple reasonable implementation directions, unclear migration scope, or a prior reviewer found the architecture direction wrong.
   - Planner drafts a plan into `/tmp/<task>-plan-r{n}.md`.
   - Reviewer critiques the plan into `/tmp/<task>-plan-review-r{n}.md`.
   - Judge evaluates plan plus review immediately.
   - If not accepted after round 2, stop and ask the user for direction.
3. Phase 2 Dev, parallel where useful:
   - Backend-only work: one backend worker.
   - Frontend-only work: one frontend worker.
   - Cross-stack work: backend and frontend workers in parallel with disjoint ownership.
   - Tests, docs, and pure config stay attached to the relevant implementation slice unless they are the whole task.
   - Planner/dev prompts must require self-check before final response: reread own output, identify the three most likely issues, fix them or justify why they are acceptable, then return only the cleaned result.
   - Each dev result must explicitly report one of four statuses: `DONE`, `DONE_WITH_CONCERNS`, `NEEDS_CONTEXT`, or `BLOCKED`.
   - For `DONE_WITH_CONCERNS`, resolve correctness or scope concerns before review; observations can be noted and review can proceed.
   - For `NEEDS_CONTEXT`, provide the missing context to the original dev worker instead of starting a new worker.
   - For `BLOCKED`, classify the cause as missing context, insufficient reasoning, oversized task, or incorrect plan; do not blindly retry unchanged.
4. Phase 3 Review, adversarial loop, max 2 rounds:
   - Main session collects `git diff HEAD -- ':!docs'` into `/tmp/<task>-diff.patch` when practical.
   - Reviewer agents read the diff first and spot-check full files only when context is needed.
   - Use backend/frontend reviewers according to touched areas.
   - Reviewer reports must be two-stage: first Spec Compliance against the plan, brief, and acceptance points; then Code Quality only if spec compliance passes.
   - Missing requested behavior or scope creep is a blocker.
   - Judge evaluates reviewer reports immediately; do not wait for reviewers to self-certify.
   - Send fixes back to the original dev worker when possible; do not start a fresh dev worker for the same ownership slice.
   - If not accepted after round 2, stop and ask the user for direction.
5. Phase 4 Verify and commit:
   - Main session runs final verification personally.
   - Frontend changes need real browser checks against the running page, not just build success.
   - Critical interactions need DOM/text assertions, not only screenshots.
   - Backend/data changes need API and/or database verification where relevant.
   - Run relevant build/test commands again from the main session.
   - Commit only after user approval.

## Review Severity

- `blocker`: data loss, wrong calculation, invariant violation, compile/runtime error, security/auth bug, explicit requirement missing, or silent failure.
- `warning`: performance, readability, maintainability, naming, or thin tests.
- `nit`: style, formatting, docs, or minor naming.
- Reviewer nits go to `/tmp/nits-followup-<task>.md` and do not trigger another fix loop.
- Judge PASS/FAIL decisions consider blockers and warnings, not nits.
