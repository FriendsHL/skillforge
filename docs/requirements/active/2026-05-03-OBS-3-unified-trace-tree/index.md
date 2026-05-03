# OBS-3 跨 session unified trace tree

---
id: OBS-3
mode: mid
status: delivered
priority: P1
risk: Mid
created: 2026-05-03
updated: 2026-05-03
---

## 摘要

OBS-2 完成后父子 session 的 trace 互相**独立可观测但不串联**。父 trace 的 waterfall 看到 `TeamCreate` / `SubAgent` tool span 后，必须用 `SubagentJumpLink` 跳到 child session 才能看 child 在做什么。多 sub-agent 并行场景（如 6f18ecca 派 3 个 child）需要跳 4 次，且看不到"父等待 N 个 child"的整体时间线。

OBS-3 在 **UI 层** 做 unified trace tree：父 trace waterfall 内嵌套显示 child trace 的 LLM/tool/event spans（缩进 + 折叠），跨 session 边界但**数据模型不动**（child trace 仍独立 `t_llm_trace` 行）。

## 阅读顺序

1. [PRD](prd.md) — 范围 / 验收点 / 锁定决策 / 风险
2. [技术方案](tech-design.md) — 后端 DFS endpoint + 前端嵌套渲染 + lazy load + WS 推送

## 当前状态

**已交付（2026-05-03）**。OBS-2 M0-M4 已交付（`b31cfaf`）；OBS-3 commit hash 待补。M5 单写观察期跟 OBS-3 并行（OBS-3 改读 layer，跟 M5/M6 cleanup 写 layer 不冲突）。

| 改动 | 状态 |
|---|---|
| BE: GET /api/traces/{traceId}/with_descendants + DFS service | ✅ |
| BE: WS trace_finalized 事件 | ✅ |
| FE: NestedWaterfallRenderer (full + mini modes) | ✅ |
| FE: 3 callsites 改造（SessionWaterfallPanel + Traces.tsx + RightRail mini）| ✅ |
| FE: Multi-session WS subscribe（parent + 每 descendant session 一个 socket）| ✅（解决 BE Reviewer W2 channel mismatch） |
| 测试：21 BE cases + 8 FE NestedWaterfallRenderer cases | ✅ |

## 链接

| 文档 | 链接 |
| --- | --- |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 关联 | [OBS-2 PRD](../2026-05-02-OBS-2-trace-data-model-unification/prd.md)（OBS-3 是 OBS-2 后续 enhancement） |
