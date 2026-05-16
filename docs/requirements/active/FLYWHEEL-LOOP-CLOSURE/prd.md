# FLYWHEEL-LOOP-CLOSURE PRD

---
id: FLYWHEEL-LOOP-CLOSURE
status: design-draft
owner: youren
priority: P1
risk: Mid
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-16
updated: 2026-05-16
---

## 摘要

修飞轮 ⑤ A/B 4 缺口 + canary logic disable，让 V3 attribution approve → 真闭环自动跑到 promoted/published。

## 已 Ratify 决策 (2026-05-16)

| # | 决策 | 锁定理由 |
|---|---|---|
| 1 | **canary logic disable** (不删 code) | 未来加回灰度只需打开 FE + enable cron 几行改动；删 code 风险高，丢失 V2 工作 |
| 2 | **SkillDraft auto-fill 跟 V3.1 PromptImprover 同款 pattern** | 复用现有成熟工程实践 (hardcoded xiaomi-mimo + mimo-v2.5-pro + max_tokens 4000 + REQUIRES_NEW + audit-trail rethrow + BYPASS checkEligibility) |
| 3 | **A/B trigger 用 `@EventListener`** | 跟 V3.2 SkillAbCompletedEvent / V4 BehaviorRulePromotedEvent listener 同款 pattern，及时不需 cron delay |
| 4 | **system agent eval 同期做** (`/run-ab` endpoint 加 fallback: 没 EvalScenario 时从 pattern.members 动态抽 N session 生 临时 scenario) | 让 attribution-curator self-improve 路径也能跑通，不只 user agent；小工作量 ~1 天 leg |

## 用户流程

### Operator 视角 (期望)

1. attribution-curator cron `0 15 * * * *` 跑写 proposal_pending
2. Operator dashboard `/insights/optimization-events` 看 pending → 点 "Approve" 一条
3. **以下全自动**:
   - V3 approve → candidate_ready (sync, 现有)
   - `@EventListener` 接 candidate_ready → 调 `/run-ab` (本期新)
   - A/B 跑（用 agent EvalScenario，或 fallback 从 pattern members 抽，本期新 leg）
   - A/B 阈值过 → auto-promote (现有)
   - promoted → 直接 production (跳过 canary，本期 logic disable)
4. Operator 收 WS toast: "Skill X promoted to production"

### Operator 视角 (skill surface, 类似)

1. approve event id=12 (skill surface)
2. **本期新**: SkillDraftService.createDraftFromAttribution 真 LLM fill SKILL.md content (跟 V3.1 prompt 同款) → SkillDraft 真完整
3. `@EventListener` 接 candidate_ready (本期新) → 调 skill `/run-ab`
4. SkillAbEvalService.createAndTrigger (现有) → 跑 baseline vs candidate
5. 阈值过 → auto-promote (现有) → 直接 enabled=true (跳 canary)

## 功能需求

### F1. attribution candidate → A/B endpoint (新)

**Prompt path**:
- `POST /api/agents/{agentId}/prompt-versions/{versionId}/run-ab`
- body `{baselineVersionId?: string (default: current active prompt version), evalScenarioIds?: string[] (default: agent's held-out scenarios; if empty, fallback 抽 pattern.members)}`
- 实现: 调 PromptImproverService.runAbTestAgainst(baselineVersionId, candidateVersionId, scenarios)
- 异步 fan-out, 返回 abRunId

**Skill path**:
- `POST /api/skills/{parentSkillId}/abtest-from-draft` (或扩展现有 `/abtest` endpoint)
- body `{candidateDraftId: string (UUID), evalScenarioIds?}`
- 内部 merge draft → real SkillEntity（如果还没 merge）→ createAndTrigger

### F2. SkillDraft attribution sync LLM fill (跟 V3.1 PromptImprover 同款)

`SkillDraftService.createDraftFromAttribution(eventId, patternId, description, expectedImpact, changeType, ownerId, suggestedSkillName)` 当前只写 stub。本期改：

- 加 `LlmProviderFactory llmProviderFactory` 字段 + Spring 注入
- 加 `EXTRACT_PROVIDER_NAME = "xiaomi-mimo"` + `EXTRACT_MODEL = "mimo-v2.5-pro"` 常量
- 加 `generateCandidateSkillMdFromAttribution(parentSkill, attributedDescription)` 私有方法
  - 拿 parent skill 的现 SKILL.md (如有) + attribution description
  - LLM prompt 引导输出 markdown frontmatter (triggers + required_tools + skill_path) + body 实际改进 SKILL.md content
  - max_tokens=4000 防 reasoning model 吃光
- service 端 parse LLM 输出 → 填 `triggers / required_tools / skill_path / 实际 content`
- `@Transactional(REQUIRES_NEW)` 防 rollback-only 污染外层 approve()
- audit-trail rethrow: LLM 失败先 save draft with content="" + log + rethrow

跟 V3.1 PromptImproverService.startImprovementFromAttribution 完全同款 pattern。

### F3. @EventListener candidate_ready → A/B (新)

新 `OptimizationEventStageListener.onCandidateReady(stageChangeEvent)`:
- @Async + REQUIRES_NEW
- 检测 event.surface_type → 走 prompt 或 skill `/run-ab` path
- 拿 event.candidate_prompt_version_id / event.candidate_skill_id (本期同时 fix V3 漏 wire 这两个字段) → 调 endpoint
- 失败 log + fire AttributionEventBroadcaster broadcastStageTransition with stage=ab_failed

config:
- `flywheel.auto-trigger-ab-on-candidate-ready: true` (default true)

### F4. /run-ab fallback: 从 pattern members 生临时 scenario (system agent leg)

`PromptImproverService.runAbTestAgainst()` 路径：
1. 拿 agent.id → query t_eval_scenario 现有 held_out scenarios
2. 如果 empty → fallback:
   - 找 event.patternId (从 candidate prompt version 关联 / 本期 fix V3 candidate_prompt_version_id link)
   - query t_pattern_session_member → 拿 3 个 session_id
   - 每 session 调 SessionScenarioExtractorService.extractFromSession() 生 临时 EvalScenarioEntity (status='ephemeral', 跑完 A/B 自动 delete)
3. 用这 3 个 scenario 跑 baseline + candidate

同款 skill path。

### F5. canary logic disable

**FE**:
- `components/skills/SkillAbPanel.tsx` 删掉 `<CanaryPanel>` embed
- `pages/Insights.tsx` 删 'canary' tab (如有)
- `components/behaviorRules/BehaviorRuleEvolutionPanel.tsx` 删 `<CanaryPanel>` embed (V4 加的)
- "Start Canary" / "Publish" / "Rollback" 按钮全去掉
- api/canary.ts 不动（保留供未来 import）

**BE**:
- `AttributionApprovalService.ALLOWED_TRANSITIONS` 添加边 ab_passed → promoted (跳过 canary_started)
- 保留 ab_passed → canary_started 边 (向后兼容)，但 V3 attribution 路径走新边

**Cron**:
- V87 migration: `UPDATE t_scheduled_task SET enabled=false WHERE name='metrics-collector-hourly';`
- (可选) `WHERE name LIKE '%canary%' OR description LIKE '%canary%'`

**保留代码 + schema**: t_canary_rollout / t_canary_metric_snapshot / CanaryRolloutService / Controller / metrics-collector 都不删。

### F6. V3 stage-mirror listener 漏 link 修补 (附带 bug 修)

V3.2 commit `99df219` listener 实现时漏了把 `candidate_prompt_version_id` / `candidate_skill_id` 写回 `t_optimization_event` 行（dogfood 验证 event 31 candidate_prompt_version_id=NULL 暴露）。本期顺便修：
- AttributionApprovalService.runCandidateGeneration 内 prompt path: `event.setCandidatePromptVersionId(versionId)` before saveAndFlush
- 同款 skill / behavior_rule path

## 非目标

- **不删 V2/V4 canary 代码** (logic disable，未来加回容易)
- **不替换 V2 SkillAbEvalService.runAbTest 逻辑** (复用)
- **不动 V4 OptimizableSurface / AbstractAbEvalRunner** (Iron Law)
- **不动核心 7+1 BE + 3 FE 文件**
- **不引入新 LLM provider / 新 schema** (复用 xiaomi-mimo / mimo-v2.5-pro)
- **不做 V5 transcript 反馈 loop** (EVAL-FEEDBACK-LOOP backlog)
- **不引入 tau-bench / 不做 lifecycle_hook surface / 不做 prompt canary**

## 验收标准

### 代码

- [ ] V87 migration 加 `UPDATE t_scheduled_task SET enabled=false WHERE name='metrics-collector-hourly';`
- [ ] PromptImproveController 加 `POST /api/agents/{agentId}/prompt-versions/{versionId}/run-ab`
- [ ] SkillDraftController 加 `POST /api/skills/{parentSkillId}/abtest-from-draft`
- [ ] SkillDraftService.createDraftFromAttribution sync LLM fill (跟 V3.1 同款)
- [ ] OptimizationEventStageChangeEvent + Publisher + Listener candidate_ready → run-ab
- [ ] `/run-ab` endpoint fallback: 没 EvalScenario 从 pattern.members 生临时 scenario
- [ ] AttributionApprovalService.runCandidateGeneration 修 V3.2 漏 link
- [ ] ALLOWED_TRANSITIONS 加 ab_passed → promoted 直接边
- [ ] FE SkillAbPanel / BehaviorRuleEvolutionPanel 删 CanaryPanel embed
- [ ] FE Insights 删 canary tab

### 测试

- [ ] PromptImproveControllerTest 加 /run-ab happy / 404 / 没 EvalScenario fallback case
- [ ] SkillDraftServiceTest 加 createDraftFromAttribution LLM fill happy / null backward-compat / LLM 失败 audit-trail rethrow case
- [ ] OptimizationEventStageListenerTest 加 onCandidateReady prompt / skill / behavior_rule case
- [ ] PromptImproverService.runAbTestAgainst fallback 抽 pattern members case
- [ ] AttributionApprovalServiceTest 加 candidate_prompt_version_id link 写回 regression case
- [ ] FE SkillAbPanel.test.tsx 加 "no canary panel" assertion
- [ ] mvn -pl skillforge-server -am test → BUILD SUCCESS
- [ ] tsc + npm build EXIT=0
- [ ] Iron Law 核心 7+1 + 3 FE 文件 git diff = 0

### Dogfood e2e

- [ ] approve event 31 (prompt surface) → 自动跑到 promoted（无人工，~3-5 min A/B 时长）
- [ ] approve event 12 (skill surface, reuse) → SkillDraft 真填 + 自动 A/B + auto-promote
- [ ] system agent agent_id=9 (attribution-curator) 自改进路径：用 pattern.members 生 临时 scenario A/B 真跑通
- [ ] dashboard 不再显示 canary 任何 UI
- [ ] t_canary_rollout table 仍存在但 0 new row (no operator 触发，路径关闭)

## 后续 backlog (V6+ 之后)

- **EVAL-FEEDBACK-LOOP** (V5 transcript → 打标 + 归因 + 调 candidate 回路) — Full
- **canary re-enable** (多用户阶段需要灰度时 FE 切回来 + cron enable)
- **FLYWHEEL-VISUAL-STATUS** (Mid ~2-3 天 9 步可视化，独立需求包已建)
- **SYSTEM-AGENT-TYPING** (Mid ~2-3 天 agent_type enum + 监控面板，独立需求包已建)
- **DYNAMIC-SIM-LIVE-TRANSCRIPT** (Light ~30 行 BE WS broadcast)
