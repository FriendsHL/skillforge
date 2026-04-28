# Memory v2 PRD

---
id: MEMORY-v2
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-27
updated: 2026-04-28
---

## 摘要

实现 Memory v2 的 schema、ACTIVE 过滤、task-aware recall、增量抽取、embedding add-time dedup、状态机、容量淘汰和 UI 批量操作。

## 目标

- 写入前后都有去重和冷却机制。
- 召回支持 L0/L1 task-aware filtering。
- 记忆状态可管理、可恢复、可淘汰。
- Dashboard 支持 tabs、batch actions、restore 和 capacity banner。

## 非目标

- 不在本期实现完整 Memory Eval 闭环。
- 不依赖 P14 多轮评测基础设施。

## 功能需求

- V29 schema。
- 增量抽取 cursor / idle scanner / cooldown。
- ACTIVE 过滤和 MemorySearch 排重。
- ACTIVE/STALE/ARCHIVED 状态机。
- 容量淘汰和 batch status API。
- 前端 memories tabs + batch actions。

## 验收标准

- [x] MemoryConsolidatorTest + MemoryServiceTest 通过。
- [x] Dashboard build 通过。
- [x] ACTIVE/STALE/ARCHIVED 状态可管理。

## 验证预期

- 后端 memory service/consolidator tests。
- 前端 build。
