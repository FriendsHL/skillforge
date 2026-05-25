# FLYWHEEL-AB-AGENT-AWARE-DATASET — MRD

## 用户痛点 (BEHAVIOR-RULE-AB-EVAL V1 dogfood 真活暴露)

### 触发：Event #123 真活跑完后用户洞察

BehaviorRule v1 `3802f6c4` 跑了 21.5 分钟 49 scenarios A/B，得 baseline 81.6% / candidate 83.7% / delta +2.04pp。**数据看上去 PASS，但分布拆开看就崩**：

| dataset 构成 (V112 mixed v1) | 数量 | rule_owner_agent 跑这题的合理性 |
|---|---|---|
| 30 generic benchmark (agent_id=null, GAIA/τ-bench/AgentBench) | 30 | ✅ 通用 benchmark 跑任何 agent OK |
| 11 Design Agent dogfood (rule 的真实目标) | 11 | ✅ |
| 6 Main Assistant dogfood | 6 | ❌ wrong agent on wrong task |
| 1 Code Agent / 1 Research Agent dogfood | 2 | ❌ wrong agent on wrong task |

**78% 题目用 Design Agent 跑 (其它 agent 的题 + 30 通用 benchmark)** — delta +2.04pp 信号被噪声稀释，不能直接回答 "rule 对 Design Agent 有用吗"。

### 用户原话 (2026-05-25 02:00 CST)

> "跑的时候 这个 behavior_rule 应该就是针对某一个 agent 和其数据集，毕竟是从他的 session 里面提取出来需要优化的点。"

> "benchmark 应该有一些通用的 就是全部 agent 都适用的。然后有一些是针对于不同场景的 agent 比如说 code agent 更适合编码的，design agent 更适合前端 交互的。"

## 用户需要

| 维度 | 期望 |
|---|---|
| A/B 跑啥 dataset | rule_owner_agent 的 **agent-specific subset** + **general benchmark subset**，不跑别的 agent 的 dogfood |
| benchmark 分类 | 区分 `general`（任何 agent 都能跑，如 GAIA 通用 task-solving）vs `agent-specific`（code agent 编码题 / design agent 前端交互题等）|
| Scenario 标签 | EvalScenario 加 `applicable_agent_roles` 维度，能多 role（一题可同时标 `["design","general"]`）|
| dual-criteria 自然对齐 | target subset = agent-specific (rule 真实影响)；regression subset = general (rule 不该害通用任务) |
| 现有 49 题 backfill | V117 一次性给 30 generic + 11 design + 6 main_assistant + 1 code + 1 research 打 role 标 |
| FE 体感 | Dataset 页面有 role 维度 filter；OptEvents row 显 rule owner role 标签便于诊断 |

## 验收点 (V1 MVP)

| AC | 验证方式 |
|---|---|
| **AC-1** V117 migration apply 后，49 scenarios 全有 `applicable_agent_roles` 非空 | `SELECT COUNT(*) FROM t_eval_scenario WHERE jsonb_array_length(applicable_agent_roles) > 0` = 49 |
| **AC-2** Backfill 分布正确 | `SELECT applicable_agent_roles, COUNT(*) GROUP BY ...`: 30 `['general']` + 11 `['design']` + 6 `['main_assistant']` + 1 `['code']` + 1 `['research']` |
| **AC-3** BehaviorRuleAbEvalService 重跑 Event #123 → 只跑 41 题 (11 Design target + 30 general regression) | `t_behavior_rule_ab_run.target_count=11 AND regression_count=30 AND target_count+regression_count=41` |
| **AC-4** target_delta_pp 不再 null | `target_delta_pp IS NOT NULL` |
| **AC-5** Wrong-agent scenarios 不跑 | log 不出现 "agent=Main Assistant" / "agent=Code Agent" / "agent=Research Agent" 在 Event #123 重跑路径 |
| **AC-6** Promote 决策不变（regression 通过 + target 显式给出） | UI Promote button enabled 当 dual-criteria 满足 |
| **AC-7** FE Dataset 页 role filter 工作 | 4 个 segmented tabs (All / General / Design / Code / Research / Main Assistant) 切换显示正确 subset |
| **AC-8** OptEvents behavior_rule row 显 owner role tag | "Design Agent" tag 显示在 row 上 |

## 不在 V1 范围 (V2+ backlog)

- agent-specific benchmark 题库扩充（SWE-bench-Lite code 题 / UI 题 / search 题）— V2 单独 scope
- Auto-classify scenarios by task text → role (LLM-based heuristic)
- Cross-tenant dataset sharing
- Dataset diff UI v1 vs v2
- Prompt surface A/B 同款 agent-aware filter（先看 behavior_rule 跑通再推广）
- 多 role rule promotion 策略 (rule 跨 role 的 promotion criteria)
- Observability traceId fix (单独 backlog)
- WS broadcast userId fix (单独 backlog)

## 关联 V1 dogfood 已知 gap

| Gap | 状态 |
|---|---|
| **本需求** Wrong-agent A/B noise | V1.1 范围（本需求修）|
| UI 没传 userId 给 BE → WS broadcast skip | 单独 backlog |
| observability traceId null warning | 单独 backlog |
| AbEvalPipeline.runSingleScenario scenario 自带 agent vs caller def 关系 | 本需求顺手澄清/不动 |
