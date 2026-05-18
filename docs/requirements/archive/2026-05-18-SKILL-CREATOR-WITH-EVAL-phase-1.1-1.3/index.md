---
id: SKILL-CREATOR-WITH-EVAL
mode: full
status: design-draft
priority: P1
risk: Mid
created: 2026-05-18
updated: 2026-05-18
---

# SKILL-CREATOR-WITH-EVAL — skill-creator skill 加 evaluation 实施 (借鉴 cc agentskills.io)

## 摘要

V1 时已建 `system-skills/skill-creator/SKILL.md` (7 步 workflow 含 evaluation 概念 + references/eval-guide.md), 但 **evaluation 部分概念在 SKILL.md 写了, 没真实施** —— 没 scripts/, 没 evals/, `SkillCreatorService.java` 仅 render SKILL.md 零 evaluation logic.

本需求**补齐 evaluation 实施** —— 跟 cc [agentskills.io evaluating-skills](https://agentskills.io/skill-creation/evaluating-skills) 同款 with-skill vs without-skill 对照 + LLM judge + benchmark + rejected 处理. 复用现 SkillForge 框架 (SubAgent 异步派发 / EvalJudgeTool V5 judgeMultiTurnConversation / t_skill_draft / EphemeralScenarioCleanupService V6 pattern), **不动 V1-V7 飞轮主路径**, 不动 Iron Law 核心 7+1 BE + 3 FE.

## r2 spec review fix (2026-05-18, subagent path — team agent stuck)

r1 fix 后 architect Opus r2 re-review 抓 4 新 must-fix (2 blocker + 2 warning). **本 spec 已 r3 inline fix**:
- draftId Long → String (SkillDraftEntity.id 真 String UUID)
- SubAgentRunCompletedEvent 不存在 → callback hook pattern (不动 SubAgentRegistry)
- importSkill return type → ImportResult
- MAX_ACTIVE_CHILDREN_PER_PARENT=5 vs 2N — serial dispatch
- Phase 编号顺序乱修正

详 prd.md "r2 spec review fix" section + tech-design.md 内 r3 fix 注.

## r1 spec review fix (2026-05-18)

architect Opus spec review (subagent + team `skill-creator-with-eval-review` 双 reviewer) 抓 6 个 spec-vs-code 真 blocker, **本 spec 已 r2 fix** (详 prd.md "r1 spec review fix" + tech-design.md 内 r2 注). 关键 fix:
- 4 入口 hook signature 跟现 code 真 align
- SubAgent async 收集模式改 2 阶段 (dispatchEvaluation + AFTER_COMMIT listener V6 pattern)
- EvalJudgeTool 真 signature 3-arg + 返 EvalJudgeMultiTurnOutput
- V91 加 4 column (现 SkillDraftEntity 缺 target_agent_id/candidate_skill_id/source)
- SubAgentTool schema 扩 skillIdsOverride
- risk 章节补 3 footgun

## 范围

Full pipeline, ~1 周:

1. **`system-skills/skill-creator/scripts/`** 新加 evaluation 相关脚本 + SubAgent launcher prompt template
2. **`system-skills/skill-creator/evals/`** skill-creator 自评 (meta dogfood, 验本期 evaluation 实施真 work)
3. **`SkillCreatorService.java`** 加 `evaluateSkillDraft(draftId, sessionIdsOrUploadedEvals)` method —— 跑 SubAgent fork × 2 with/without + 调 EvalJudgeTool + 写 benchmark
4. **V91 migration**: `t_skill_draft` 加 `evaluation_result_json` 字段 + source 枚举加 `'skill-creator-eval'` + status 加 `'rejected'`
5. **EphemeralScenarioCleanupService 复用** (V6 已有) cleanup evaluation 跑完的 ephemeral scenarios
6. **Dashboard report panel** FE 显示 evaluation 结果 (benchmark.json + LLM 总结 + delta)

跨 4 skill 创建入口统一接入:

| 入口 | scenario 来源 | hook 点 |
|---|---|---|
| 用户上传 | zip 内 `evals/evals.json` | SkillUpload path 调 SkillCreatorService.evaluateSkillDraft |
| Marketplace 下载 | zip 内 evals/ (没的话 fallback 通用 scenario pool) | SkillImportService 路径 |
| 自然语言描述 (agent attach skill-creator) | agent step 1 顺手问用户给 2-3 use case + test case 合 1 轮 | skill-creator SKILL.md step 4-5 SubAgent path |
| Extract from sessions | 原 session 当 test case (turn 1 user prompt + 原 agent response baseline) | SkillDraftService.extractFromRecentSessions 调 evaluateSkillDraft |

## 不在范围内

- **不动 SkillAbEvalService 内部** (V2/V4 baseline=PARENT_SKILL / EMPTY_SKILL path 保留)
- **不动 EvalJudgeTool prompt** (复用现 V5 judgeMultiTurnConversation, 不改成 per-assertion evidence-based grading — 那是另一需求 `EVAL-ASSERTIONS-EVIDENCE` 候选 backlog)
- **不动 V1-V7 飞轮 9 步主路径** (本需求是 skill 创建入口加 eval gate, 不是飞轮内部改造)
- **不引入 blind comparison judge** (judge 知顺序, 接受 bias risk)
- **不实施 30 天 NO_SKILL "skill 必要性 check" cron** (backlog item B 阶段 3 候选)

## 阅读顺序

1. [MRD](mrd.md) — 痛点 + 用户场景 + 跟 cc agentskills.io 对照
2. [PRD](prd.md) — F1-F8 功能 + 验收点 + 4 入口流程图
3. [技术方案](tech-design.md) — V91 schema + SubAgent path + 4 入口 hook 点 + Phase 拆分

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| cc 借鉴方法论 | https://agentskills.io/skill-creation/evaluating-skills |
| cc 借鉴实施参考 | https://github.com/anthropics/skills/tree/main/skills/skill-creator |
| CC-SKILL-EVAL-METHODOLOGY backlog | [需求包](../../backlog/CC-SKILL-EVAL-METHODOLOGY/index.md) |
