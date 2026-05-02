# OBS-2 Trace 数据模型统一

---
id: OBS-2
mode: full
status: draft
priority: P1
risk: Full
created: 2026-05-02
updated: 2026-05-02
---

## 摘要

OBS-1 引入了 `t_llm_trace` / `t_llm_span` 双表（trace 实体 + 丰富 LLM span），但只覆盖 LLM 维度；tool / event span 仍写入旧的 `t_trace_span`。结果：

- **trace 概念分裂在两张表**：trace 列表查询要走 `t_trace_span where span_type='AGENT_LOOP'`；trace 聚合要走 `t_llm_trace`。
- **span 跨两张表**：LLM 查 `t_llm_span`（字段全），tool/event 查 `t_trace_span`（字段简）。
- **跨表 merge + limit 截断 bug**（已用 `limit=1000` 临时缓解，参见 SessionDetail.tsx:178）。
- **`t_session_message` 没 `trace_id`**：messages 没法按 trace 切分，per-trace 视图只能 fallback 到 session 级。

OBS-2 把 `t_llm_trace` / `t_llm_span` 扩展为统一 trace + span 表（加 `kind` 列支持 tool/event），改造写入路径，迁移历史数据，关闭 `t_trace_span` 双写，最终让查询模型回到清晰的 `user → session → trace → span` 链。

## 阅读顺序

1. [MRD](mrd.md) — 痛点与排查动机
2. [PRD](prd.md) — 范围 / 里程碑 / 验收点 / 待拍板决策
3. [技术方案](tech-design.md) — schema 改动、写入路径改造、迁移策略、回滚预案

## 当前状态

**草稿（2026-05-02）**。前置 Phase A（双轨数据回填到 `t_llm_trace` / `t_llm_span` 共 33 trace + 176 span）已完成，未提交（纯数据层动作）。OBS-2 主体待 PRD 用户批准 → Full 档 Plan 阶段 → Dev → Review 对抗 2 轮。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 前置（Phase A） | 见 [delivery-index.md](../../../delivery-index.md) 2026-05-02 行（待补） |
| 关联 | [OBS-1 归档](../../archive/2026-04-29-OBS-1-session-trace/) |
