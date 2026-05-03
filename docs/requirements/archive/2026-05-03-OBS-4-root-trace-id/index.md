# OBS-4 跨 agent / 跨 session trace 串联（root_trace_id）

---
id: OBS-4
mode: full
status: archived
priority: P1
risk: Full
created: 2026-05-03
updated: 2026-05-03
archived: 2026-05-03
milestones_done: M0, M1, M2, M3, M4
milestones_pending: -
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

**Archived（2026-05-03）**。M0-M4 全部交付，整体在 1 天内完成（vs 原估 1.5-2 周开发 + 1 周观察）— 因 PRD 完整 + 单人主导 + Full Pipeline 简化（Plan 跳 / Mid 简化路径 / 一次 fix 不循环）。

| 里程碑 | 范围 | 状态 | commit |
|---|---|---|---|
| M0 | schema migration（V44 + V45 nullable + 回填 + 索引；V46 推到 M1） | ✅ | `eda937d` |
| M1 | 写入路径全链路（ChatService 4-arg overload / AgentLoopEngine 透传 / PgLlmTraceStore SQL 加列 INV-2 immutable / SessionService 4 方法 / spawn 复制 active_root / V46 SET NOT NULL / 4 新 IT 锁 INV-1~6） | ✅ | `e89c593` |
| M2 | Read API（`GET /api/traces/{rootId}/tree` 返回全部 traces + spans + depth；`GET /api/traces?sessionId=X` 加 rootTraceId 字段） | ✅ | `877495b` |
| M3 | FE 瀑布流二级折叠 inline group 渲染（WaterfallFoldTeamRow + WaterfallChildSummaryRow + 默认全收起 + 老 session 零变化） | ✅ | `a496a7e` |
| M4 | 真实场景观察 + 归档收尾 | ✅ | (本 commit) |

## M4 真实场景验证

session `cf4202a6-2bea-481b-a134-009a4d59a855`（用户实测 2026-05-03 21:50）— OBS-4 第一个真实使用：

```
root_trace_id = fefdb51a-23eb-4a9f-935f-45fcf6af4869
共 7 traces / 5 sessions / 60 spans (28 llm + 32 tool)

13:50:13  cf4202a6 ★ Main(self-root)        4 tool / 30.8s   user message 起点
13:50:37  cf4202a6   TeamCreate × 3 (并行 spawn ±1ms)
   ├──→  bcd7eae7   Main(inherit)           12 tool / 56.2s
   ├──→  05c6a996   Main(inherit)            8 tool / 87.5s
   └──→  16a4c657   Main(inherit)            8 tool / 99.0s
13:51:34  cf4202a6   Main(inherit) [汇总]    0 tool /  5.6s   收 child 结果
13:52:05  cf4202a6   Main(inherit) [汇总]    0 tool /  3.3s
13:52:16  cf4202a6   Main(inherit) [汇总]   33.3s              最终汇总
```

INV-1/3/4/5/6 全部在生产数据上验证通过。`SELECT * FROM t_llm_trace WHERE root_trace_id = 'fefdb51a-...'` 一条 SQL 拿全 7 traces，正是 PRD §1 描述的"长程调研派多 child + 主 agent 多次收结果汇总"完美实例。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 关联前置 | [OBS-2 trace 数据模型统一](../2026-05-02-OBS-2-trace-data-model-unification/) |
| 取代 | OBS-3 unified trace tree（todo backlog，将标 superseded） |
