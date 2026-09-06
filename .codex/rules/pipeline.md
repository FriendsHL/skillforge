# Risk-Based Codex Pipeline

This file owns task triage and execution. `think-before-coding.md` owns planning
and clarification; `code-review.md` owns severity; `verification-before-completion.md`
owns verification commands. Read specialty rules through the AGENTS index.

## Triage

Classify the changed behavior and failure boundary, not brief length, file size,
line count, or module count. Mixed work inherits the highest actual risk; split
independently verifiable increments when useful.

- **Solo:** docs/comments, mechanical edits, harmless constants, or bounded pure
  functions with strong tests, provided no Full boundary below changes.
- **Mid:** ordinary bug fixes, UI behavior, established-pattern features, build
  configuration, and test changes without a Full boundary. This is the default.
- **Full:** changes to any of these boundaries:
  - Message persistence/rewrite identity, JSON shape, compact pairing, summary
    roles, boundaries, or recovery semantics.
  - Provider wire protocols, streaming tool/reasoning/cache/usage handling,
    retry/idempotency, or shared FE/BE/iOS contracts.
  - Authentication/authorization, secrets, untrusted execution, or file/network
    access controls.
  - Schema migrations, new persistence entities, JPQL/native query semantics,
    or transactional consistency.
  - Lock ownership, leases/heartbeats, cancellation, concurrent state ownership,
    or lifecycle-hook execution contracts.
  - Broad architecture migrations or iOS boundaries in `ios-pipeline.md`.

Core areas (`AgentLoopEngine`, engine hooks, LLM/compact code, `ChatService`,
`SessionService`, repositories, Chat UI, Lifecycle Hooks editor) trigger careful
inspection and relevant specialty rules. Editing a label or isolated presentation
inside one does not itself require Full. Changing its protected semantics does.
When the failure boundary is uncertain, investigate before downgrading.

## Execution

1. **Research:** inspect the task diff and relevant docs/code; reuse local patterns.
   Record acceptance points and identify affected invariants. Use the planning
   rule only when a material decision needs resolution.
2. **Implement:** main session by default. Add workers only for useful independent
   slices with explicit file ownership; keep tests with the behavior they verify.
3. **Review:**
   - Solo: inspect the diff.
   - Mid: perform one focused self-review against acceptance points and relevant
     checklists. Use an independent reviewer when uncertainty warrants it.
   - Full: request an independent reviewer when tools permit and the work is
     separable. Otherwise do a distinct checklist-based review and disclose the
     lack of independent review. Never present self-review as independent.
   - Check both requirement compliance and correctness/security, even if one
     already fails. Fix blockers; resolve or explicitly report warnings. Nits do
     not trigger another loop. Follow-up review checks fixes and affected paths.
4. **Verify:** the main session inspects the integrated diff and runs the relevant
   gates from `verification-before-completion.md`. Existing evidence from the
   current turn can be reused if it covers the final unchanged state; rerun after
   relevant edits, integration changes, failures, or unresolved concerns.
5. **Report:** describe behavior, evidence, and material gaps. Commit or push only
   with user authorization.

If a new Full boundary appears, retain useful work, upgrade the review, and run
its verification. Repeated failures require a new hypothesis or plan; ask the user
only when a material decision or missing input prevents progress, not because an
arbitrary number of review rounds elapsed.

## Tool And Agent Use

- Use tools actually exposed by the current runtime; do not assume named planning,
  discovery, parallel-call, or Claude-specific tools exist.
- Batch independent reads when supported. Keep dependent edits and checks ordered.
- Subagents inherit the current model unless the user requests otherwise. Give
  them bounded scope, ownership, relevant rules, and acceptance criteria.
- Workers must preserve unrelated edits and report changes, checks, and unresolved
  concerns or blockers. Reuse the original worker for follow-up when practical.
- The main session integrates results and judges evidence. Agent reports alone
  do not prove completion.
- Use worktrees when isolation helps; a dirty workspace alone does not require
  one if the task can safely avoid unrelated edits.
- Plans, review templates, and temporary artifacts are optional aids, not required
  ceremony. Use `review-verdict.md` when a complex review needs a structured record.
