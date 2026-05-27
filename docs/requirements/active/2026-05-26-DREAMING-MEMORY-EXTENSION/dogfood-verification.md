# Dreaming Memory Dogfood Verification

Date: 2026-05-28 Asia/Shanghai (2026-05-27 UTC)

## Commands

- `mvn -pl skillforge-server test`
- `mvn -pl skillforge-server -Dtest=ProposeOptimizationToolTest,AttributionApprovalServiceTest,AttributionApprovalServiceBehaviorRuleTest,SkillDraftServiceAttributionTest,PromptImproverServiceAttributionTest test`
- Start current worktree server on port `18080` with external datasource `jdbc:postgresql://localhost:15432/skillforge`
- `POST /api/admin/memory/llm-synthesis/run-once`
- SQL checks for `flyway_schema_history`, `t_memory_proposal.evidence_json`, and `t_memory`
- `POST /api/admin/memory/proposals/25/approve?reviewerUserId=1`

## Result

- Backend tests: `mvn -pl skillforge-server test` passed with `BUILD SUCCESS`; 2220 tests run, 0 failures, 0 errors, 117 skipped.
- Task5 focused regression tests: passed with `BUILD SUCCESS`; 49 tests run, 0 failures, 0 errors.
- Server start: current worktree service started on `localhost:18080`; Flyway applied V120, V121, V122, and V123 successfully.
- Run-once response:

```json
{
  "ok": true,
  "ran": "memory-curator-scheduled-task",
  "taskId": 3,
  "runId": 160,
  "sessionId": "5985f67a-b143-4eed-8926-ff2e893a116a",
  "status": "queued"
}
```

- Parent scheduled run result: `t_scheduled_task_run.id=160` finished with `status='success'`.
- Transcript-backed proposals:

| proposalId | type | sourceMemoryIds | evidence |
| --- | --- | --- | --- |
| 25 | reflection | `[]` | `evidence_json` includes transcript `sessionId`, `seqNo`, and `quote` entries |
| 26 | reflection | `[]` | `evidence_json` includes transcript `sessionId`, `seqNo`, and `quote` entries |

- No direct memory write before approval:
  - `select count(*) from t_memory where synthesis_run_id = 'synth-a7f3b2e1-9d4c-4e8a-b5f6-1c2d3e4f5a6b';` returned `0`.
  - `select count(*) from t_memory where synthesis_run_id like 'dream-%' and created_at > now() - interval '1 hour';` returned `0`.
- Approval result:

```json
{
  "ok": true,
  "appliedType": "reflection",
  "reason": null
}
```

- Approval DB check:
  - `t_memory_proposal.id=25` is now `status='approved'`, `reviewed_by_user_id=1`.
  - `t_memory.id=161` was created with `status='ACTIVE'` and `synthesis_run_id='synth-a7f3b2e1-9d4c-4e8a-b5f6-1c2d3e4f5a6b'`.

## Residual Risks

- The `memory-curator` scheduled task dispatches per-user sub-agent sessions asynchronously. The parent scheduled run can finish after dispatch; proposal creation happens in the child session.
- The implementation plan's approval URL included `/llm-synthesis/proposals/...`, but the actual controller path is `/api/admin/memory/proposals/{id}/approve`.
- Dogfood used the existing local PostgreSQL on `localhost:15432`; a separate current-worktree server was started on `18080` because an older main-workspace server already occupied `8080` and held the embedded Postgres lock.
