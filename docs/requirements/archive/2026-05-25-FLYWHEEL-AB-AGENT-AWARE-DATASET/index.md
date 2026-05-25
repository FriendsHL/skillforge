# FLYWHEEL-AB-AGENT-AWARE-DATASET — V1 占位记录

> 创建：2026-05-25
> 状态：active（**未开 Plan pipeline**，记录 + ratify 决策框架完成，待开工时补完整 mrd/prd/tech-design）
> 模式：Full pipeline 候选（schema 改 t_eval_scenario + Service 改 BehaviorRuleAbEvalService 跨 BE+FE + V117 migration）
> 触发：BEHAVIOR-RULE-AB-EVAL V1 dogfood (commit 700ac29 → cc7286b → 7fd4226) Event #123 A/B 真活验证暴露 design gap

## 背景

### BEHAVIOR-RULE-AB-EVAL V1 dogfood 真活发现

Event #123 (Design Agent attribution → BehaviorRule v1 `3802f6c4`) approve 后 UI 触发 retry → A/B 跑 21.5 分钟 49 scenarios → 返 baseline 81.6% / candidate 83.7% / delta +2.04pp regression-pass.

**但数据细看（用户洞察）**:
- BehaviorRule v1 是 **Design Agent** 的 attribution（不是 Main Assistant）
- A/B 用 Design Agent 的 def 跑了 **mixed dataset 49 scenarios** 分布:
  - 30 generic benchmark (agent_id=null, GAIA / τ-bench / AgentBench)
  - 11 Design Agent (本来的目标)
  - 6 Main Assistant
  - 1 Code Agent
  - 1 Research Agent
- **78% 题目用 wrong agent 跑** (Design Agent 跑 GAIA / Main / Code / Research 题)
- +2.04pp delta 信号被噪声稀释 — 真正"该 rule 对 Design Agent 影响"被埋

### 用户原话 (2026-05-25)

> "跑的时候 这个 behavior_rule 应该就是针对某一个 agent 和其数据集，毕竟是从他的 session 里面提取出来需要优化的点。"

> "benchmark 应该有一些通用的 就是全部 agent 都适用的。然后有一些是针对于不同场景的 agent 比如说 code agent 更适合编码的，design agent 更适合前端 交互的。"

## 5 个 ratify 决策（已用户初步同意，开 Plan 时再次确认 + 细化）

| # | 决策 | 选 |
|---|---|---|
| **D1** | EvalScenario 加 `applicable_agent_roles JSONB` 字段（e.g. `["design","general"]`）→ 灵活，通用题不复制到每个 agent dataset | (a) |
| **D2** | Agent role taxonomy 5 个: `general` / `code` / `design` / `research` / `main_assistant`（V2 可扩） | 5 role |
| **D3** | A/B 跑时 dataset = agent-specific subset + general subset 合并 → dual-criteria 自然兼容 (`general` 当 regression check / `agent-specific` 当 target) | (a) |
| **D4** | V117 migration backfill 现有 49 scenarios role：30 benchmark → `general` / 11 Design → `design` / 6 Main → `main_assistant` / 1 Code → `code` / 1 Research → `research` | backfill |
| **D5** | V1 scope: 框架 + 现有 49 reclassify + Event #123 重跑验证；agent-specific 题库扩充留 V2 | MVP |

## 核心交付（待开工时细化）

- **V117** migration: t_eval_scenario 加 `applicable_agent_roles JSONB NOT NULL DEFAULT '[]'`（不可空 default empty array，跟 V114 rule_trigger_hints 同款 pattern）+ GIN partial index + V117 内 UPDATE backfill 49 行 role 标签
- **V118** (可选): seed agent-specific benchmark scenarios（code-agent: SWE-bench-Lite micro / design-agent: 前端 UI 题 / research-agent: search&summarize 题）— V2 backlog 也行
- **Java**: EvalScenarioEntity 加 `applicableAgentRoles` 字段; EvalScenarioDraftRepository 加 finder by role (`jsonb_exists_any(applicable_agent_roles, ...)` 复用 cc7286b hot-fix 模式)
- **Service**: `BehaviorRuleAbEvalService.runAsync` 加 agent role resolve + dataset filter:
  1. resolve rule_owner_agent.agent_type / role
  2. target subset = scenarios where `applicable_agent_roles ∋ {<owner_role>}`
  3. regression subset = scenarios where `applicable_agent_roles ∋ {'general'}`
  4. union 后跑 A/B
- **FE**: EvalDatasets page 加 role filter tab; behaviorRule.ts type 加 role 字段；OptimizationEvents 在 behavior_rule row 显 rule owner role 标签

## 验收点（待细化）

- AC-1: V117 apply 后 49 scenarios applicable_agent_roles 全非空（30 general + 11 design + 6 main_assistant + 1 code + 1 research）
- AC-2: BehaviorRuleAbEvalService 重跑 Event #123 → A/B 只跑 Design 11 + General 30 = 41 scenarios（不再跑 Main/Code/Research 7 题）
- AC-3: target_delta_pp 不再 null（Design subset 11 题真活算出）
- AC-4: regression_delta_pp 用 general 30 题作 anchor
- AC-5: FE Dataset 页 role filter 真活 work

## 不在 V1 范围 (V2+ backlog)

- agent-specific benchmark 题库扩充（SWE-bench / UI 题 / search 题）
- 跨多个 agent role 的 rule promotion 策略
- LLM-as-judge 评 role 适配性
- Auto-classify scenarios by task text → role

## 关联 V1 已知 design gap (BEHAVIOR-RULE-AB-EVAL V1 dogfood)

- ⚠️ **Wrong-agent A/B noise**: 本需求修
- ⚠️ **UI 没传 userId 给 BE → WS broadcast skip**: 单独 backlog 项（Controller 从 SecurityContext 拿 userId 传 service）
- ⚠️ **observability traceId null**: BehaviorRuleAb 跑 scenario 时没设 traceId context → LlmSpan warning（不阻塞）；单独 backlog
- ⚠️ **AbEvalPipeline.runSingleScenario 设计层 SRP**: scenario 自带 agent 还是 caller 强 override — 跟本需求融合一起 ratify
