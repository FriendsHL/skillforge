# SKILL-CREATOR-WITH-EVAL PRD

---
id: SKILL-CREATOR-WITH-EVAL
status: design-draft
owner: youren
priority: P1
risk: Mid
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-18
updated: 2026-05-18
---

## 摘要

补齐 `system-skills/skill-creator/` skill 的 evaluation 实施 (V1 时 SKILL.md 写了概念没真做). 加 scripts/ + evals/ + `SkillCreatorService.evaluateSkillDraft` + V91 schema + dashboard report panel. 跨 4 skill 创建入口 (上传 / 下载 / 自然语言 / extract) 统一接 evaluation gate. 不动 V1-V7 飞轮 9 步主路径.

## r4 path decision (2026-05-18 用户 Phase 1.0 取证后拍板)

Phase 1.0 be-dev verify 发现 spec D2 "SubAgent path" 实施时必须 (a) 扩 SubAgentTool schema (b) 加 session-level skill override 持久化机制 (c) 改 ChatService.runLoop load override 替换 default agent.skillIds — **触 Iron Law 红灯** (ChatService 核心 7+1).

push back 推荐 Path e (sandbox 复用 V2 SkillAbEvalService pattern, 0 动 Iron Law). 用户**拍板 Path d3** (保 spec D2 SubAgent path, 接受动 Iron Law). 理由: SubAgent path 更"端到端真实跑" (走完整 ChatService 路径, 跟生产对话 100% 一致), 业务上 Sandbox path 更内部.

### d3 实施 path 关键技术点

1. **V92 migration**: ALTER TABLE t_session ADD COLUMN skill_overrides_json TEXT NULL (V91 已 reserve 给 t_skill_draft, 加新 V92)
2. **SessionEntity** 加 `skillOverridesJson` field + getter/setter
3. **SubAgentTool schema 扩** `skillIdsOverride: List<Long>` 字段 (现 schema 6 字段, 加第 7)
4. **SubAgentTool.handleDispatch** 改: if `skillIdsOverride != null` → 创建 child SessionEntity 时 set `skillOverridesJson = JSON.stringify(skillIdsOverride)` 写入 t_session
5. **ChatService.runLoop 改 (Iron Law 红灯)**: 读 `session.skillOverridesJson` 决定 agent.skillIds 用哪份 — non-null override / null 现有逻辑. **改动量 ~5-10 行 1 个 if-else branch**, 不触 java.md footgun #4 持久化字节一致 / #5 t_session_message identity 列 (本 column 在 t_session 不在 t_session_message)
6. **SkillCreatorEvalCoordinator** 新 service: `@Async + @Transactional(REQUIRES_NEW)` listen `SessionLoopFinishedEvent` (V1 已 publish ChatService.java:1021 + P12 ScheduledTaskExecutor 已用 — be-dev Phase 1.0 verify ✓), 通过 parentSessionId / subAgentRunId 反查识别 SubAgent child session, 计数 2N 完成 → aggregate + judge + write evaluation_result + status
7. **SkillCreatorService.dispatchEvaluation(String draftId, List<String> scenarioIds)** 主 caller: render transient SkillEntity 拿 candidate_skill_id (V6 R3 promoteDraftToTransientSkill pattern 复用) + Serial dispatch 2N SubAgent (skillIdsOverride=[candidateSkillId] for with / [] for without) + 注册 coordinator listener + 返 runIds
8. **Phase 2.0 review 重点**: java-reviewer **必须显式 audit ChatService 改动 Iron Law 红灯**, 按 java.md known footgun 全面 verify (尤其 invariant 不变 + 测试覆盖)

工作量 +1d vs Path e (主要 ChatService 改动 + reviewer 严 + V92 migration).

## r2 spec review fix (2026-05-18 architect Opus re-review, subagent path — team agent stuck)

r1 fix 后 architect Opus r2 re-review 抓 4 新 must-fix (2 blocker + 2 warning) — r1 fix 真正解 4/6 blocker (A3/A4/A6 + SubAgentTool schema 扩 + risk 3 footgun ✓), 但 A1/A2/A5 各漏一刀. **本 spec 已 r3 inline fix**:

- **r3-blocker-1**: `draftId Long → String` (SkillDraftEntity.id 真是 String UUID, verify line 18-19) — 3 处 occurrences 全 fix
- **r3-blocker-2**: `SubAgentRunCompletedEvent` 不存在 (grep verify 0 file) → 改 callback hook pattern (path b: SkillCreatorEvalCoordinator 直接 hook `SubAgentRegistry.onSessionLoopFinished` callback, **不动 SubAgentRegistry 核心 Iron Law**)
- **r3-warning-3**: 入口 2 `importSkill(...)` return type `SkillEntity → ImportResult` (verify SkillImportService.java:107)
- **r3-warning-4**: MAX_ACTIVE_CHILDREN_PER_PARENT=5 vs 2N dispatch — 采 **serial dispatch** 模式不超 cap (V1 SubAgentRegistry.java:43 verify)
- **r3-nit**: Phase 编号顺序乱 (1.0/1.1/1.2/1.3/**2**/1.6/Final) → 修正 1.0/1.1/1.2/1.3/2.0/3.0/4.0 严格顺序

team agent infra 3 个 reviewer (architect × 2 + code-reviewer × 1) 全 stuck idle 不 act on spawn brief, 切 subagent path 拿到详 grep verify 行号 verdict + r3 inline fix 完.

## r1 spec review fix (2026-05-18 architect Opus verify)

architect Opus spec review 抓 6 个 spec-vs-code 真 blocker, **本 spec 已 r2 fix**, 详 tech-design.md 内 r2 注:
- A1 4 入口 hook signature 重写跟现 code 真 align (`SkillService.uploadSkill` / `SkillImportService.importSkill(Path,...)` / `extractFromRecentSessions(Long, Long) → int`)
- A2 SubAgent async 收集模式: 改 2 阶段 (sync dispatchEvaluation + async `onSubAgentRunCompleted` AFTER_COMMIT listener, 跟 V6 OptimizationEventAutoTriggerListener 同款 pattern)
- A3 EvalJudgeTool signature 改 3-arg + 返 `EvalJudgeMultiTurnOutput` (compositeScore + overallScore); latency/cost 是 EvalOrchestrator wall-time + token 算
- A4 cleanupEphemerals 参数改 `List<String>` (EvalScenarioEntity.id 是 String UUID)
- A5 V91 加 `target_agent_id BIGINT` + `candidate_skill_id BIGINT` + `source VARCHAR(64)` 3 column (现 SkillDraftEntity 缺), 加 transient SkillEntity 物化时机 doc
- A6 source 加 column (跟 A5 一起)

Plus SubAgentTool schema 扩 `skillIdsOverride` (现 schema 无字段); B3 risk 章节补 3 个 footgun (async × @Transactional / transient 物化 / 5 维 score 拼合).

## 决策记录 (2026-05-18 用户拍板)

| # | 决策点 | 选项 |
|---|---|---|
| D1 | skill-creator 形态 | **SkillForge zip 包 skill** (`system-skills/skill-creator/`, SystemSkillLoader 加载), 不是 system agent |
| D2 | 评测实施路径 | **SubAgent path** — agent 调 skill-creator 时 SubAgent fork × 2 (with_skill / without_skill), 跟 SKILL.md step 5 原写一致 + cc 同款 + 复用 V1 SubAgent infra |
| D3 | LLM judge | **复用 V5 EvalJudgeTool.judgeMultiTurnConversation** (composite/quality/efficiency/latency/cost 5 维 score) |
| D4 | 评测集 (scenario) 来源 | 跨 4 入口不同, 全转 ephemeral EvalScenarioEntity (V6 ephemeral pattern 复用): (1) 上传 zip evals/evals.json (2) marketplace zip evals/ (3) 自然语言 → agent step 1 顺手问 (4) extract → 原 session turn 1 prompt + 原 response baseline |
| D5 | baseline | **NO_SKILL** — target agent skillIds=[] 完全不挂 skill 重跑 (clean baseline 不复用原 session 历史 response) |
| D6 | 输出形态 | benchmark.json + LLM 总结 + dashboard report panel (with/without pass_rate + delta + token + latency) |
| D7 | delta < 阈值处理 | **status='rejected'** + LLM 总结 + **不 allow force-promote** (低质量直接挡, operator 看 reject reason iterate) |
| D8 | 数据 schema 复用 | **`t_skill_draft`** V91 加 4 column (target_agent_id BIGINT / candidate_skill_id BIGINT / **source VARCHAR(64) NULL — 现 entity 无此字段, V91 加**, enum 'upload'/'marketplace'/'natural-language'/'extract-from-sessions'/'attribution'/'manual' / evaluation_result_json TEXT) + status 加 'evaluated_passed'/'rejected' 枚举 |

## 功能需求

### F1. skill-creator skill 加 scripts/ + evals/

`system-skills/skill-creator/scripts/`:
- `run-eval.md` — SubAgent prompt template, 描述怎么 fork 2 sub-session (with_skill + without_skill) 跑 scenario
- `judge-prompt.md` — 调 EvalJudgeTool 的 prompt wrapper (跟 V5 judgeMultiTurnConversation 接口对齐)
- `benchmark.json.example` — 输出 schema 示例

`system-skills/skill-creator/evals/evals.json`:
- skill-creator 自评 (meta dogfood): 写 2-3 test case 验本 evaluation 实施真 work
- test prompt 示例: "创建一个分析 CSV 的 skill" → expected: 生 SKILL.md + 跑 eval + report

### F2. `SkillCreatorService.evaluateSkillDraft(draftId, scenarios)`

新加 Java method, 流程:
1. 接收 `SkillDraftEntity draft` + `List<EvalScenarioEntity> ephemeralScenarios`
2. SubAgent fork × 2:
   - **with_skill**: payload = `{agentId: draft.targetAgentId, skillIds: [...currentSkills, draftSkillId], scenario: ...}` → spawn → 拿 transcript
   - **without_skill**: payload = `{agentId, skillIds: [], scenario}` → spawn → 拿 transcript
3. 对每 transcript 调 `EvalJudgeTool.judgeMultiTurnConversation(transcript, scenario)` → composite/quality/efficiency/latency/cost score
4. 聚 benchmark: with/without 各自 mean pass_rate + 5 维 score + delta + token_cost + duration
5. 调 LLM 生总结 (这 skill 加/减 value 原因, 给 operator 看)
6. 写 `draft.evaluationResultJson = benchmark + summary`
7. 判 delta ≥ 阈值 (5pp pass_rate default):
   - 是 → `draft.status = 'evaluated_passed'`
   - 否 → `draft.status = 'rejected'` + reason in summary
8. 调 `EphemeralScenarioCleanupService.cleanupEphemerals(scenarioIds)` (V6 已有)
9. return benchmark + summary 给 caller

### F3. V91 migration (t_skill_draft schema 扩展)

```sql
ALTER TABLE t_skill_draft ADD COLUMN evaluation_result_json TEXT NULL;
-- JSON: { with_skill: {pass_rate, quality, efficiency, latency, cost}, without_skill: {...}, delta: {...}, llm_summary, source_session_ids: [], evaluated_at }

-- source 字段值新增枚举 'skill-creator-eval' (跟现 'extract_from_sessions' / 'attribution' / 'manual' 并列)
-- status 字段值新增 'rejected' (跟现 'draft' / 'approved' / 'discarded' / 'evaluated_passed' 并列)
-- CHECK constraint 可选: chk_skill_draft_status / chk_skill_draft_source 收紧 enum (灵活 ENUM 用 VARCHAR, 不加 CHECK)
```

### F4. 4 入口接入 hook

| 入口 | 入口实施位置 | 调 evaluateSkillDraft 时机 |
|---|---|---|
| 用户上传 | SkillUpload controller / SkillImportService | zip 解析完成 → 提取 zip 内 `evals/evals.json` → 转 ephemeral EvalScenarioEntity → call evaluate |
| Marketplace 下载 | SkillImportService.importSkill | 同上 (zip 来源不同, 流程同) |
| 自然语言描述 (agent attach skill-creator) | skill-creator skill 自己 (SKILL.md step 4-5) 触发 SubAgent | agent step 1 顺手问用户给 2-3 test case → step 4-5 SubAgent fork × 2 跑 (走 SkillCreatorService.evaluateSkillDraft 路径) |
| Extract from sessions | SkillDraftService.extractFromRecentSessions | 现 method 流程完成后 (生 SkillDraft) → call evaluate, scenarios=sessions 转 ephemeral |

### F5. Dashboard report panel

新建 / 扩 `SkillDraftDetailDrawer.tsx` 加 evaluation report tab:
- benchmark table: with_skill / without_skill / delta 5 维 score
- LLM summary 段落 (这 skill 加/减 value 原因)
- source session_ids drill-down link (跳 SessionList?agentId=&sessionIds=)
- ✅ promote button (status='evaluated_passed' 才显示) / ❌ reject reason 显示 (status='rejected')

### F6. Reject list FE 入口

新建 `pages/RejectedSkillDrafts.tsx` 或 `SkillDrafts.tsx` 加 tab:
- 列 status='rejected' draft + LLM reason + source + evaluated_at
- operator 可点 iterate (跳 skill-creator chat 触发 re-evaluate based on rejection reason)

### F7. skill-creator skill SubAgent fork 集成

复用 V1 `SubAgentTool` (CLAUDE.md known). skill-creator agent 调:
```
SubAgent(
  parentSessionId=<current>,
  taskPrompt=<scenario.task>,
  agentDef=<target agent>,
  skillIdsOverride=<draftSkillId or []>,  // override for with/without
  sessionMetadata={evaluationContext: true, draftId: X, scenarioId: Y, baseline: 'with_skill'|'without_skill'}
)
```
SubAgent 跑完 result 自动回推 (V1 设计), skill-creator master 收集 N × 2 transcript 后调 judge.

### F8. Iron Law

- 核心 7+1 BE + 3 FE 文件 git diff = 0
- 不动 SkillAbEvalService / EvalJudgeTool prompt / V2-V4 A/B 框架内部
- 不动飞轮 9 步主路径 (本需求是 skill 创建入口加 gate)
- t_skill_draft 加 column 是 low-risk schema change

## 非目标

- 不实施 per-assertion evidence-based grading (留独立 backlog `EVAL-ASSERTIONS-EVIDENCE`)
- 不改 SkillAbEvalService baseline mode (V2/V4 PARENT/EMPTY 保留)
- 不实施 30 天 NO_SKILL cron
- 不实施 blind comparison
- 不引入新 LLM provider / 新 schema 表
- 不动 Iron Law

## 验收标准

### 代码

- [ ] `system-skills/skill-creator/scripts/{run-eval.md, judge-prompt.md, benchmark.json.example}` 3 新文件
- [ ] `system-skills/skill-creator/evals/evals.json` skill-creator 自评 2-3 case
- [ ] V91 migration: t_skill_draft 加 evaluation_result_json + source/status enum 扩
- [ ] `SkillCreatorService.evaluateSkillDraft(draftId, scenarios)` Java method 实施
- [ ] 4 入口接入 evaluateSkillDraft hook (SkillUpload / SkillImportService / skill-creator SubAgent / SkillDraftService.extractFromRecentSessions)
- [ ] EphemeralScenarioCleanupService 复用 (V6 已有, 不动)
- [ ] FE SkillDraftDetailDrawer 加 evaluation report tab
- [ ] FE Rejected drafts list panel

### 测试

- [ ] BE: SkillCreatorServiceEvaluateTest (with_skill > without_skill / with_skill < without_skill → rejected / scenario 来源 4 路径分别测)
- [ ] BE: SubAgent fork integration test (mock SubAgentTool 验 payload + 收集 transcript)
- [ ] BE: V91 migration IT
- [ ] BE: 4 入口 controller test (SkillUpload + SkillImportService + SkillDraftService.extractFromRecentSessions 调 evaluate)
- [ ] FE: SkillDraftDetailDrawer.test.tsx evaluation tab render
- [ ] FE: RejectedSkillDrafts.test.tsx list render

### 验证

- [ ] mvn -pl skillforge-server -am test 全绿
- [ ] tsc + npm build EXIT=0
- [ ] Iron Law 核心 7+1 BE + 3 FE git diff = 0
- [ ] 真活: 用 agent (Main Assistant) attach skill-creator skill → 自然语言"创建一个 csv-analyzer skill" → agent step 1 问 + step 4-5 SubAgent fork → benchmark.json 真生 → t_skill_draft.status 真改

## 后续 backlog

- `EVAL-ASSERTIONS-EVIDENCE`: EvalScenario 加 assertions + EvalJudge prompt 改 per-assertion evidence (cc 同款 grading)
- 30 天 NO_SKILL "skill 必要性 check" cron (评 existing production skill 真 value)
- Blind comparison judge (judge LLM 不知 with/without 顺序, 防 bias)
- Skill iterate workflow (operator 点 reject draft → 跳 skill-creator chat 让 agent 基于 reject reason 改 SKILL.md 重跑)
