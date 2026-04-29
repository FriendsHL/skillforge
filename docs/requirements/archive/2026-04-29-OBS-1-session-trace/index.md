# OBS-1 Session x Trace 合并详情视图

---
id: OBS-1
mode: full
status: delivered
priority: P1
risk: Full
created: 2026-04-28
updated: 2026-04-29
delivered: 2026-04-29
---

## 摘要

建设一个统一的 session 排查视图，把对话消息、trace span、完整 LLM request / response payload 串起来。BUG-F、BUG-G、compact、provider 切换这类问题，仅靠被截断的 trace 摘要无法可靠判断。

## 阅读顺序

1. [MRD](mrd.md) - 用户痛点和排查背景。
2. [PRD](prd.md) - MVP 范围和验收标准。
3. [技术方案](tech-design.md) - 当前技术决策和设计说明。

## 当前状态

**已交付（2026-04-29）**。MVP（OBS-1-1 / OBS-1-2 / OBS-1-3）按 plan-r3 + R2/R3 修订全部上线；OBS-1-4 / OBS-1-5 推迟到 V2。所有 Q1~Q8 决策已定（全部接受 default）。Plan 经 3 轮对抗 review PASS（plan-r1/r2/r3 + judge-r1/r2/r3），代码经 3 轮 code review（review-r1/r2 + judge-r1/r2，R3 修复后用户决定跳过 R3 复审直接交付）。

实施过程中识别并修复了若干 plan 之外的关键 bug（详见 [tech-design.md §交付补丁](tech-design.md)）：
- `LlmSpanEntity` JSONB 列缺 `@JdbcTypeCode(SqlTypes.JSON)` → LLM span 写库 100% 失败
- `LlmObservabilityConfig` 与 Flyway 形成循环依赖 → 启动失败（拆出 `ObservabilityFlywayConfig` 解决）
- `R__migrate_legacy_llm_call.sql` GUC 路径无设值代码 → ETL 永远 skip（改用 `${etl_mode}` placeholder 直判）
- ETL 对已有 live 行不去重 → legacy/live 重复（V36/V37 dedup + R__ NOT EXISTS guard）
- `Message.getTextContent()` 不识别 `tool_result` 类型 → 所有 TOOL_CALL trace span output 永远是空字符串（pre-existing bug，顺手修）
- 4 个 OBS-1 controller 缺 `@RequestParam Long userId` 校验 → 认证 bypass（R3 修补，复用 `ChatController.requireOwnedSession` 模式）

## 从旧 ToDo 合并的关键信息

- MVP 子任务 OBS-1-1 到 OBS-1-5 已并入 PRD。
- Q1 到 Q8 决策清单已并入 PRD。
- `skillforge-observability` 模块边界、Observer 模式、A3 ETL、字段映射、主链路保护已并入技术方案。
- 旧原文仍可在 [legacy ToDo](../../../references/legacy-todo-2026-04-28.md) 中追溯。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | 见 [delivery-index.md](../../../delivery-index.md) 2026-04-29 行 |
