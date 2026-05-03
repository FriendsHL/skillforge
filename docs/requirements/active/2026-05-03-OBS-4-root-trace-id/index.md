# OBS-4 跨 agent / 跨 session trace 串联（root_trace_id）

---
id: OBS-4
mode: full
status: planning
priority: P1
risk: Full
created: 2026-05-03
updated: 2026-05-03
milestones_done: M0, M1, M2, M3
milestones_pending: M4 (观察 + 收尾)
---

## 摘要

当前 `trace` 实体的语义太窄 = "单次 LLM call 内的 iteration loop"。一次完整的用户请求实际上跨多 trace + 多 session：

```
user query
 └─ 主 agent trace_1  (LLM call → 决策派 subagent → trace 结束)
 └─ subagent_A trace_a   ← 独立 session，独立 trace
 └─ subagent_B trace_b   ← 独立 session，独立 trace
 └─ 主 agent trace_2  (主 agent 收 child 结果后新一轮 LLM call → 汇总)
```

物理上 4 个 trace + 3 个 session，没有任何字段把它们关联起来。导致：

- 父 session 瀑布流只能看 `trace_1` 或 `trace_2` 单独一段，看不到完整执行链
- 想了解 subagent 调了什么工具 / 模型必须 SubagentJumpLink 跳转 child session（实测案例 `6f18ecca` 派 5 个 child 需点 4 次跳转）
- 类似分布式 trace 缺一个 root span ID（OpenTelemetry 的 distributed tracing 概念）

OBS-4 引入 `root_trace_id` 一等公民字段：每个 user message 起一个新 root，主 agent 后续 trace + 派出的所有 subagent（含递归 child of child）trace 全部继承同一个 `root_trace_id`。前端瀑布流升级为按 `root_trace_id` 维度展示，TeamCreate row 折叠组 inline 展开 child agent 的工具 / 模型调用，不离开当前页面。

## 阅读顺序

1. [MRD](mrd.md) — 用户实际遇到的痛点（含 `6f18ecca` 现场）
2. [PRD](prd.md) — 范围 / 4 个里程碑 / 验收点 / 已拍板决策
3. [技术方案](tech-design.md) — schema 改动、写入路径继承规则、读 API、FE 二级折叠渲染

## 当前状态

**Planning（2026-05-03）**。决策点已全部拍板，等 PRD review 通过即开 M0。

| 里程碑 | 范围 | 工期估算 | 状态 | commit |
|---|---|---|---|---|
| M0 | schema migration（V44 + V45 nullable + 回填 + 索引；V46 SET NOT NULL 推到 M1） | 0.5 天 | ✅ | `eda937d` |
| M1 | 写入路径全链路（ChatService 4-arg overload / AgentLoopEngine 透传 / PgLlmTraceStore SQL 加列 INV-2 immutable / SessionService 4 方法 / spawn 复制 active_root / V46 SET NOT NULL / 4 新 IT 锁 INV-1~6） | 2-3 天 | ✅ | 待 commit |
| M2 | Read API（`GET /api/traces/{rootId}/tree` 返回全部 traces + spans + depth；`GET /api/traces?sessionId=X` 加 rootTraceId 字段） | 1 天 | ✅ | 待 commit |
| M3 | FE 瀑布流二级折叠 inline group 渲染（WaterfallFoldTeamRow + WaterfallChildSummaryRow + 默认全收起 + 老 session 零变化） | 3-5 天 | ✅ | 待 commit |
| M4 | 观察 1 周 + todo backlog 标 OBS-3 superseded | — | ⏳ | — |

**总计：~1.5-2 周开发 + 1 周观察期**

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 关联前置 | [OBS-2 trace 数据模型统一](../2026-05-02-OBS-2-trace-data-model-unification/) |
| 取代 | OBS-3 unified trace tree（todo backlog，将标 superseded） |
