# OBS-2 Trace 数据模型统一

---
id: OBS-2
mode: full
status: done
priority: P1
risk: Full
created: 2026-05-02
updated: 2026-05-11
milestones_done: Phase A, M0, M1, M2, M3, M3.5, M4, M5, M6
milestones_pending: none
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

**已完成（2026-05-11）**。M0-M3 完成 schema / 双写 / ETL / API+前端切读；M4 已关闭旧写入；M5 观察通过；M6 已 drop `t_trace_span` 并完成真实库验证。

| 里程碑 | 状态 | commit |
|---|---|---|
| Phase A（数据修复，前置）| ✅ | 直接 SQL（无 commit） |
| M0（schema migration）| ✅ | `edd5a21` |
| M1（写入路径双写）| ✅ | `4412729` |
| M2（历史数据迁移 ETL）| ✅ | `26023c5` |
| M3（API + 前端切读）| ✅ | `69ee35b` |
| M3.5（观察期）| ✅ | dev DB 双轨一致已确认 |
| M2 ETL 真触发（前置 M4）| ✅ | 直接 SQL 等价跑：33 legacy trace 转 ok/error + 920 tool + 4 event 回填 |
| M4（关闭 t_trace_span 写入）| ✅ | `b31cfaf` |
| M5（观察期，单轨）| ✅ | `t_trace_span` max(start_time)=`2026-05-02 16:17:16 UTC`，M4 后无新增旧表写入 |
| M6（drop t_trace_span）| ✅ | `V67__drop_trace_span.sql`；真实库 `flyway_schema_history` version=67 success；`to_regclass('public.t_trace_span')` 为空 |

## 交付状态

OBS-2 已按 M6 收尾为单轨模型：运行时读写路径以 `t_llm_trace` / `t_llm_span` 为准，旧 JPA 实体与 repository 已移除，`TraceCollectorImpl` 保留为 no-op 兼容 bean，legacy hook history 读旧表路径退役。开发库已应用 V67，`t_trace_span` 不再存在。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 前置（Phase A） | 见 [delivery-index.md](../../../delivery-index.md) 2026-05-02 行（待补） |
| 关联 | [OBS-1 归档](../2026-04-29-OBS-1-session-trace/) |
