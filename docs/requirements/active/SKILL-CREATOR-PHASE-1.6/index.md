---
id: SKILL-CREATOR-PHASE-1.6
mode: mid
status: ratified
priority: P2
risk: Mid
created: 2026-05-18
updated: 2026-05-18
follows: SKILL-CREATOR-WITH-EVAL
---

# SKILL-CREATOR-PHASE-1.6 — skill-creator-with-eval 真接 LLM judge + 真 fire entries

## Ratify (2026-05-18, 启动前拍板)

跟用户 2 小时讨论 (LLM-as-judge vs agent-as-judge vs state-grounded oracle 路径选择) 后**回到原 spec 简单路径**:

| 决策 | 拍板 |
|---|---|
| **F1 judge 范式** | LLM-as-judge (复用 V5 EvalJudgeTool.judgeMultiTurnConversation), **不 state-grounded oracle**, 不 agent-as-judge. 跟 cc agentskills.io 同款 transcript-only judge |
| **F2 entries 触发模式** | operator dashboard **手动 trigger** (F4), 不自动 fire on upload/import/extract. resolveAnyAgentIdForOwner 删, controller 加 `?targetAgentId=N` 参数 |
| **F3 transient skill 写磁盘** | 条件性 — F1 dev 实施时 grep `SkillRegistry.getSkillDefinition` 看是否真要 disk SKILL.md 才决定. 不需要的话本 F 跳过 (现 ChatService.runLoop 走 skill_overrides_json setSkillIds names, registry name lookup 不一定要 disk) |
| **F4 dashboard UI** | Modal (不 inline form), trigger button 在 SkillDraftDetailDrawer 现 footer 加 (status='draft' / 'rejected' 时显). target_agent picker = Select (filter agentType=user, 排 system) |
| **delta threshold** | 沿用 Phase 1.1 hardcode 5pp, 不暴露 operator slider (避免 UX 复杂). 改阈值留 future config |
| **优化迭代场景** | Phase 1.1 设计天然支持 — `new_version` vs `old_version` 用同款 `skill_overrides_json` dispatch + 同款 aggregate. **不引入新 design**, 不在本期 scope 内验证 (Phase 1.7 候选 dogfood) |
| **EvalJudgeTool 输入是否够拼 5 维 SkillMetrics** | 够. `EvalJudgeMultiTurnOutput.compositeScore` + `overallScore` 直填; `passRate` = `count(child compositeScore >= 0.7) / N`; `avgLatencyMs` / `totalCostUsd` 从 `t_subagent_run` 拼. shape 跟 Phase 1.1 `EvaluationResult.SkillMetrics` 已定型不改 |
| **D14 — eval gate semantics on import (2026-05-19 Phase 2.0 reviewer W1 补)** | **"report 不是 production gate"** — 入口 1/2 (upload/import) 真活先 register 进 prod `SkillRegistry` 立刻 active, eval batch 在 transient sidecar `_eval_<uuid>` 上**并行**跑作 report 落到 `t_skill_draft.evaluation_result_json`. operator 看 dashboard 决定 reject 时通过 `SkillService.deleteSkill` 删 (separate path, 不在本期 scope). **defer-register-until-verdict (真 production gating) 留 backlog** (~1d Mid 候选). spec ratify 没明说要 defer, 且 parallel-report 不破其它 spec; `ImportResult.evaluating(draftId)` 只是 FE hint "report 在跑", **NOT** "skill 还没 prod active". |

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

## Phase 拆分 (Mid pipeline ~3-4d)

| Phase | 内容 | 工程量 | 验证 |
|---|---|---|---|
| **Phase 1.0** | be-dev 取证: grep verify `EvalJudgeTool.judgeMultiTurnConversation` 真接口 + `EvalJudgeMultiTurnOutput` shape + `MultiTurnTranscript.render()` + `ScenarioRunResult` 字段 + `t_subagent_run` 真存 latency/cost 字段. SkillRegistry.getSkillDefinition 真要 disk 吗 (决 F3 跳过 or 实施) | ~0.5d | 红测试 1 个: `SkillCreatorEvalCoordinatorJudgeIT` assert aggregate 不调 EvalJudgeTool (compile fail) |
| **Phase 1.1** | F1 BE — `MultiTurnTranscriptBuilder.fromSession(sessionId)` helper + `ScenarioRunResult` adapter (从 t_subagent_run + t_session 拼) + `SkillCreatorEvalCoordinator.aggregate` 重写调 `EvalJudgeTool.judgeMultiTurnConversation` × N child, aggregate 5 维 SkillMetrics | ~1.5d | mvn test 全绿; aggregateProxyReplacedTest assert 真调 EvalJudgeTool (Mockito verify), output 5 字段真填 |
| **Phase 1.2** | F2 BE — 3 entry controller 加 `?targetAgentId=N` 参 + service 真调 `dispatchEvaluation`. SkillService.uploadSkill / SkillImportService.importSkill / SkillDraftService.extractFromRecentSessions | ~1d | 3 IT: `SkillServiceUploadEvaluationIT` / `SkillImportServiceEvaluationIT` / `SkillDraftServiceExtractEvaluationIT` |
| **Phase 1.3** | F4 FE — `TriggerEvaluationModal.tsx` + SkillDraftDetailDrawer footer 加 "Trigger Evaluation" 按钮 (status='draft'/'rejected' 显) + BE controller `POST /api/skill-drafts/{id}/evaluate` | ~1d | vitest 2 case render + click trigger flow |
| **Phase 1.4** | F3 (条件性, 如 Phase 1.0 取证决需要) — renderTransientCandidateSkill 写磁盘 V6 R3 同款 pattern | ~0.3d (or 0) | 现有测试不破 |
| **Phase 2.0** | Mid review 对抗 1 轮 + Judge | ~0.5d | java-reviewer + typescript-reviewer 双 opus, blocker 升 Full / warning-only 一次 fix |
| **Phase Final** | mvn test + dashboard 真 dogfood 跑 1 个 skill end-to-end + commit | ~0.5d | dashboard trigger 真跑 → child session 真 chatAsync → judge 真打分 → Evaluation Report tab 真显 5 维 benchmark |

## Iron Law

- 核心 7+1 BE 0 diff (ChatService 本期 Phase 1.1-1.3 已 +22 行红 audit PASS, Phase 1.6 不动)
- 核心 3 FE 0 diff
- V91/V92 schema 不动 (Phase 1.1-1.3 已 land)
- footgun #4 / #5 不适用 (不动 Message / 不动 t_session_message)
- footgun #6 跨栈契约: `EvaluationResult` shape Phase 1.1 已定型, Phase 1.6 只换 source-of-data (proxy → EvalJudgeTool), FE 不需 retro 改

## 链接

- 本期前置: [SKILL-CREATOR-WITH-EVAL archive](../../archive/2026-05-18-SKILL-CREATOR-WITH-EVAL-phase-1.1-1.3/index.md) (Phase 1.1-1.3 真实施 + 4 deviation 决策 D9-D13)
- 借鉴方法论: [cc agentskills.io evaluating-skills](https://agentskills.io/skill-creation/evaluating-skills)
