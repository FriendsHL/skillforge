# FLYWHEEL-LOOP-CLOSURE MRD

---
id: FLYWHEEL-LOOP-CLOSURE
status: delivered
created: 2026-05-16
completed: 2026-05-17
---

## 痛点

2026-05-16 dogfood 手动测试 V1-V5 完整闭环时碰到 hard block:

### 1. V3 attribution candidate 跑出来但没法 A/B

Operator 在 dashboard `/insights/optimization-events` approve event id=31 (prompt surface):
- ✅ `t_optimization_event.stage` proposal_pending → candidate_ready (sync)
- ✅ `t_prompt_version` 写新行 source='attribution' content=6228 chars (LLM 真生成的 SKILL.md 改进)
- ❌ **没法触发 A/B**: PromptImproveController 唯一 endpoint `POST /api/agents/{id}/prompt-improve` 需要 `evalRunId` (V2 eval-driven A/B path)，attribution candidate 没经 EvalRun 不接

V3 attribution-curator 是基于 production session pattern 推断改进，没 EvalScenario baseline，**两条 path（eval-driven A/B vs attribution-driven）之间没 bridge**。V3 ATTRIBUTION-AGENT 实施时（commit `99df219`）显式砍了这层 wire (Phase 1.4 留作 backlog)。

### 2. SkillDraft attribution path 是 stub

approve event id=12 (skill surface) 后:
- ✅ `t_skill_draft` 写新行 name='AttrSkill6_12' status='draft'
- ❌ `skill_path` / `triggers` / `required_tools` / **actual SKILL.md content 全空** (per SkillDraftService.createDraftFromAttribution javadoc: "Triggers / requiredTools deliberately blank — Approve flow's render step will attempt to synthesize a SKILL.md from description + promptHint")
- ❌ "synthesize a SKILL.md" 这个 render step **从未 wire**

Operator 需要手动 PATCH 填字段才能 merge → 真 SkillEntity → 跑 A/B。Friction 极大，不能 dogfood 自动跑通。

### 3. candidate_ready 没自动 trigger A/B

即使有 endpoint 能跑 A/B，operator 也要手动 click：

```
stage=candidate_ready 后停在那
   ↓ ❌ 没人 listen
   操作员看到 dashboard 才点 "Run A/B"
```

V3 commit `99df219` 也明确说这层 wire 留作 backlog。

### 4. system agent (attribution-curator) 没 EvalScenario

agent 9 (attribution-curator system agent) 历史 0 个 EvalRun，因为没人给 system agent 配 EvalScenario set。所以 attribution-curator 自己改自己的提案（如 event 31 是给 agent 9 提的）即使 wire 通了 A/B endpoint，也跑不起来——没 baseline scenario 跑。

## canary 在 dogfood 阶段不需要

V2 SKILL-CANARY-ROLLOUT 默认 `rolloutPercentage=100`（一刀切），canary 是 opt-in 给多用户阶段用的。dogfood 单用户期 canary 路径每一步都 friction 高（startCanary → stepUp → publish/rollback），实际**没真用过**。

V4 期 behavior_rule canary 实施也是"接通基础设施，等真需要时打开"——但 V5 dogfood 验证发现 dogfood 期跑 ⑤→⑦ 路径反而是阻碍。

**user 决定**: 暂时砍掉 canary 路径（logic disable），让 ab_passed → promoted 直接。等真需要灰度时打开。

## 用户场景

### 场景 A: 真飞轮 dogfood 跑通

```
operator 打开 dashboard /insights/optimization-events
→ 看到 5 个 attribution-curator cron 写的 proposal_pending
→ 点 "Approve" 一条
→ 飞轮自动: candidate 真生成 (含 SKILL.md / system_prompt 实际内容) → A/B 真跑 → 阈值过 auto-promote → 直接 production 上线
→ 30 分钟内端到端验证 V1-V5 全链
```

现状: operator approve 后 **停在 candidate_ready 不动**，要手动 click "Run A/B"——但实际**找不到 button**（attribution path 没 endpoint）。

### 场景 B: stakeholder demo "看，agent 自己改进了自己"

```
"看 attribution-curator cron 跑 → 自己写了一条 candidate → 自己 A/B 测 → 自己 promote 上线"
```

现状: 演示停在 "自己写了一条 candidate"，后面**没法演示**。

### 场景 C: 单用户 dogfood 不需要 canary

```
operator: "我自己用，每个 candidate promoted 后直接给我用就行"
现状: 走 canary stepUp/publish 流程，operator 还要手动 click 3 次按钮（startCanary / 看 metric / publish）
```

期望: ab_passed → promoted → published 一气呵成。

## 不在 MRD 范围内

- **不做 V5 transcript → 打标+归因+调 candidate 回路** (那是 EVAL-FEEDBACK-LOOP backlog，V6.x 项)
- **不替换 V2 现有 SkillAbEvalService.runAbTest 逻辑** (复用)
- **不动 V4 OptimizableSurface / AbstractAbEvalRunner** (Iron Law)
- **不删 V2/V4 canary 代码** (logic disable，未来加回容易)
- **不引入新 LLM provider / 新 schema** (复用 xiaomi-mimo / mimo-v2.5-pro)

## 用户 quote / 决策来源

- 2026-05-16 user "我感觉 是不是我们现在功能都有了 A/B有吗？但是 没有闭环起来，每个环节都是需要人手动来做的"
- 2026-05-16 user "canary 这个相关的都先干掉，等 run 了一段时间 或者我们该做灰度能力的时候 在加上 现在没必要 也不需要考虑这些"
- 2026-05-16 user 4 ratify 决策（canary logic disable / SkillDraft fill 跟 V3.1 同款 / A/B trigger @EventListener / system agent eval 同期做）
