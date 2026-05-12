# TEAM-COORDINATOR-FOUNDATION 多 Agent 协作可见性基础

---
id: TEAM-COORDINATOR-FOUNDATION
mode: full
status: deferred
priority: P0
risk: Full
created: 2026-05-12
updated: 2026-05-12 (deferred — 用户判断对当前架构 ROI 不够)
---

## 摘要

Coordinator agent（原 "Main Assistant"，本期改名）在执行复杂任务时常需通过 `TeamCreate` 派发多个并行子 agent（research / design / code 等），但当前用户和 Coordinator 都看不到子 agent 的中间执行状态，只能等最终结果。本需求落基础底子：(1) Main Assistant → Coordinator 改名；(2) `AgentLoopEngine` 自动注入 `collab_member_progress` / `collab_member_started` 事件；(3) Dashboard 加 `/collab-runs/:id` 树状看板。

## 阅读顺序

1. [MRD](mrd.md) — 用户原始诉求、痛点场景、与 Anthropic Managed Agents 对照所得启发。
2. [PRD](prd.md) — 目标 / 非目标 / 验收点 / 验证预期。
3. [技术方案](tech-design.md) — 关键决策 D1-D9、BE/FE 改动、migration、风险与评审记录。

## 当前状态

- **2026-05-12 r1**：3 reviewer 并行 Sonnet → 一致 NEEDS_FIX_R2。4 blocker + 13 warning。Opus Judge 合并去重 + 修订 4 文件。
- **2026-05-12 r2**：architect PASS_WITH_WARNINGS / java + typescript NEEDS_FIX_R3（2 机械 blocker：DO $$ H2 兼容 / F1-F2 hook 签名）+ 4 warning（含 zustand 新 dep 违规）。
- **2026-05-12 r2 Judge final**：按 pipeline.md「2 round limit」不跑 r3，apply 5 fix → spec **design-ready**。
- **2026-05-12 DEFERRED**：用户决定搁置 — "对我们当前的架构没啥用"。

## 搁置原因（2026-05-12）

核实现状后发现 SkillForge 已具备本需求绝大部分价值：

- `RightRail.tsx` 4 tab（context / activity / subagent / **team**）已存在
- `TeamTab` 已显示 collab run 节点图 + peerMessages swim spans
- `ActivityTab` 基于 trace tree 已显示 loopSpans / teamSpans + tool input-output 详情（`t_llm_span` 数据齐全）

本需求**主要价值是 cond-node 节点加 iter/currentTool/tokens 字段** + 节点点击跳 ActivityTab —— 边际收益小，跟现有 trace 可见性重叠。用户判断 ROI 不抵改动成本。

**与本需求相关的潜在 backlog**（不在本需求，留候选）：

- A. **`[TeamResult]` 在 Coordinator chat 里的形态**：可能 token 浪费在重复拼上下文
- B. **`lightContext` 默认值**：当前 false，research/code agent 通常不需要 SOUL.md + 全 tools；改 true 省 30-50% input token
- C. **`TeamCreate(outputContract=)` 自然语言契约**（非 JSON Schema 验证）让 [TeamResult] 更可预测

## 复活条件

- "派 N 个子 agent 后看不见进度" 反复成为高频痛点
- ActivityTab trace tree 在 collab 场景被反复跳来跳去说明可见性入口不够顺
- A / B / C 之一变成真痛点时，可单独立项不必复活整个需求包

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | - |

## 与其它需求关系

| 需求 | 关系 |
| --- | --- |
| REQ-3 TEAM-STEER-INTERRUPT | 本需求是其前置（dashboard 树状看板里加干预按钮）|
| REQ-7 COLLAB-RUN-ANALYZER | 本需求是其前置（要有 progress 数据才能事后分析）|
| REQ-4 / REQ-5 / REQ-6 / REQ-8 | 等本需求上线 + REQ-7 分析报告反馈后再排优先级 |

不在本需求范围（明确推迟）：失败重试 / outputSchema / TeamPlan YAML / AgentCheckpoint 表 / Coordinator 自动 reviewer。详见 [PRD 非目标](prd.md)。
