# OBS-2 Trace 数据模型统一

---
id: OBS-2
mode: full
status: in-progress
priority: P1
risk: Full
created: 2026-05-02
updated: 2026-05-02
milestones_done: M0, M1, M2, M3
milestones_pending: M3.5 (1-2 周观察), M4 (关旧写), M5 (4 周观察), M6 (drop t_trace_span)
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

**进行中（2026-05-02）**。M0 / M1 / M2 / M3 已交付（双写期已开始 + API/前端切读完成）；M3.5 / M4 / M5 / M6 待跟进。

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
| M5（观察期 ~2 天，单轨）| ⏳ | — |
| M6（drop t_trace_span）| ⏳ | — |

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 前置（Phase A） | 见 [delivery-index.md](../../../delivery-index.md) 2026-05-02 行（待补） |
| 关联 | [OBS-1 归档](../../archive/2026-04-29-OBS-1-session-trace/) |
