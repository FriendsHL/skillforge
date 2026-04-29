# OBS-1 Session x Trace 合并详情视图

---
id: OBS-1
mode: full
status: prd-ready
priority: P1
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

建设一个统一的 session 排查视图，把对话消息、trace span、完整 LLM request / response payload 串起来。BUG-F、BUG-G、compact、provider 切换这类问题，仅靠被截断的 trace 摘要无法可靠判断。

## 阅读顺序

1. [MRD](mrd.md) - 用户痛点和排查背景。
2. [PRD](prd.md) - MVP 范围和验收标准。
3. [技术方案](tech-design.md) - 当前技术决策和设计说明。

## 当前状态

产品范围基本明确。MVP 做 OBS-1-1 到 OBS-1-3；OBS-1-4 / OBS-1-5 推迟到 V2。Q3、模块隔离、Observer 模式等已定；Q1、Q2、Q4、Q5、Q6、Q7、Q8 需要在 Full Pipeline plan 阶段最终确认。

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
| 交付 | - |
