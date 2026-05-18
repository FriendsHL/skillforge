---
id: SKILL-CREATOR-PHASE-1.6
mode: mid
status: backlog
priority: P2
risk: Mid
created: 2026-05-18
updated: 2026-05-18
follows: SKILL-CREATOR-WITH-EVAL
---

# SKILL-CREATOR-PHASE-1.6 — skill-creator-with-eval 真接 LLM judge + 真 fire entries

## 摘要

`SKILL-CREATOR-WITH-EVAL` Phase 1.1-1.3 (2026-05-18 archive) 落地 V91/V92 + dispatchEvaluation + 2-stage AFTER_COMMIT pattern + FE evaluation report, **但保留 4 个 placeholder 留给 Phase 1.6**:

1. **真接 `EvalJudgeTool.judgeMultiTurnConversation`** (D12) — 当前 `SkillCreatorEvalCoordinator.aggregate` 用 `runtime_status` proxy 算 5 维 SkillMetrics, 要换成真 judge call
2. **Entries 1/2/4 真 fire dispatchEvaluation** (D13) — `SkillService.uploadSkill` / `SkillImportService.importSkill` / `SkillDraftService.extractFromRecentSessions` helper 都 ready 但 skeleton hook 不真触发. 要让 operator 在 dashboard 选 target agent + 手动触发
3. **`renderTransientCandidateSkill` 写磁盘** (D11) — 现 `setSkillPath(null)` 配 judge proxy 用; 真接 EvalJudgeTool 时如发现需 disk SKILL.md (因 SkillRegistry.getSkillDefinition load 要), 补 V6 R3 同款 disk-write pattern
4. **Dashboard target-agent picker UI** — 入口 1/2/4 真 fire 需要 operator 在 dashboard 选 target agent + delta threshold + 触发按钮

## 触发条件

dogfood `SKILL-CREATOR-WITH-EVAL` 1-2 周后 + 用户/operator 反馈"想看真 judge score" 后启动.

## 范围

Mid 档, ~3-4 天 (本期 Phase 1.1-1.3 helper 全 ready 减大半工程量):

### F1 真 LLM judge 接入 (D12 deviation 补)
- `SkillCreatorEvalCoordinator.aggregate(...)` 内, 对每个 (draftId × scenario × baseline) 拉 child session t_session_message → 构造 `MultiTurnTranscript` → 拼 `ScenarioRunResult` → 调 `EvalJudgeTool.judgeMultiTurnConversation(scenario, scenarioRunResult, transcript)` 返 `EvalJudgeMultiTurnOutput`
- 把 judge 出 `compositeScore` + `overallScore` 真填进 SkillMetrics; `passRate` 算 `count(compositeScore >= 0.7) / N`; `avgLatencyMs` / `totalCostUsd` 真从 `t_subagent_run` 算
- 加 `MultiTurnTranscriptBuilder.fromSession(sessionId)` helper (复用 SessionService 拉 messages + 滤 system / tool_result 拼成 user-assistant turn)

### F2 Entries 1/2/4 真 fire (D13 deviation 补)
- **入口 1 SkillService.uploadSkill**: `maybeTriggerEvaluationForUpload` 把 `resolveAnyAgentIdForOwner` 删掉, 改 controller 接收 `?targetAgentId=N` 参 — operator 在 dashboard upload 时显式选; 没选则不 fire (skeleton 现状保留 fallback)
- **入口 2 SkillImportService.importSkill**: 类似入口 1, 加 `targetAgentId` 参 + 真调 `dispatchEvaluation`, 返 `ImportResult.evaluating(draftId)`
- **入口 4 SkillDraftService.extractFromRecentSessions**: 多个 source session 选规则 = `firstByCreatedAt` 或 operator 在 dashboard rejected list 上选; helper `buildEphemeralScenariosFromSessions` 已 ready
- 测试: `SkillServiceUploadEvaluationIT` / `SkillImportServiceEvaluationIT` / `SkillDraftServiceExtractEvaluationIT`

### F3 renderTransientCandidateSkill 写磁盘 (D11 deviation 补)
- 只在 F1 真接 EvalJudgeTool 时如发现需要 disk SKILL.md (因 SkillRegistry.getSkillDefinition 要从 disk load 才能 attach 给 SubAgent)
- 复用 V6 R3 `SkillDraftService.promoteDraftToTransientSkill` 同款 pattern 完整 disk write + cleanup
- 不需要的话本 F 跳过 (现 ChatService.runLoop 通过 skill_overrides_json setSkillIds names, SkillRegistry name lookup 即可 — 可能 disk 没必要)

### F4 Dashboard target-agent picker UI
- `SkillDraftDetailDrawer` 加 "Trigger Evaluation" 按钮 (rejected / draft status), 弹 modal 让 operator 选 target agent + 选 source session(s) + 选 delta threshold
- 上传 / import zip 路径 dashboard FE 弹同 modal
- 加 controller `POST /api/skill-drafts/{id}/evaluate` (现 SkillCreatorService.dispatchEvaluation 已 ready, 接 controller 即可)

## 不在范围内

- per-assertion evidence-based grading (`EVAL-ASSERTIONS-EVIDENCE` 单独 backlog)
- 30 天 NO_SKILL "skill 必要性 check" cron (单独 backlog 候选)
- blind comparison judge

## 链接

- 本期前置: [SKILL-CREATOR-WITH-EVAL archive](../../archive/2026-05-18-SKILL-CREATOR-WITH-EVAL-phase-1.1-1.3/index.md) (Phase 1.1-1.3 真实施 + 4 deviation 决策 D9-D13)
- 借鉴方法论: [cc agentskills.io evaluating-skills](https://agentskills.io/skill-creation/evaluating-skills)
