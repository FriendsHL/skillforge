# SKILL-CURATOR 技能低使用自动归档

---
id: SKILL-CURATOR
mode: mid
status: backlog
priority: P1
risk: Mid
created: 2026-06-20
updated: 2026-06-20
source: wiki-takeaway-triage-2026-06-20.md 缺口③（审计自荐，123 个 wiki 候选无人提，来自 capability-audit §4 的"Memory 有 curator、Skill 没有"不对称观察）
---

## 摘要

给技能（Skill）补一个**低使用自动归档的 curator**，复用 Memory 已有的 `MemoryConsolidator` 模式。

不对称现状：Memory 有完整的生命周期管家（`MemoryConsolidator` 四段：dedup / TTL-archive / capacity-demote / expired-delete），但 **Skill 没有对应的管家** —— `SkillEntity.usageCount`（用量计数）一直在记，却没有任何地方拿它做"长期没人用的技能自动降级/归档"。

## 当前状态（2026-06-20 源码查证）

- `SkillEntity.usageCount`（`SkillEntity.java:76`）字段存在；被 SkillController / SkillService / SkillDraftService / SkillEvolutionService 等读取用于**展示 / 排序 / eval**，但**没有生命周期归档消费**。
- **无 `SkillConsolidator` 类**（对照 `MemoryConsolidator` 存在）。
- 即真缺口：用量数据有，但缺"按用量自动清理废技能"的 curator。

## 用处（为什么值得做）

技能越攒越多会**污染 agent 的工具选择**——LLM 要从一堆长期没人用的废技能里挑，更慢、更易选错。定期把低使用技能降级/归档 = agent 工具集更精、选择更准更快。这是 self-curation 的一块，跟 Memory 的归档同理。

## 实现提示（lite）

- 仿 `MemoryConsolidator`：后台调度（可复用其 3-gate 触发模式）按 `usageCount` 阈值 + 最近使用时间（TTL）把低使用技能降级 / 标 archived。
- 可能需要：`SkillEntity` 加 archived 状态（或复用现有 status）+ 一条 migration + dashboard 可见（与 Memory 归档 UX 对齐）。
- **守护**：不误归档系统技能 / 高频技能 / 新建未冷却的技能；归档可逆。

## 验收点（草拟）

1. 长期低使用技能能被自动降级/归档（有阈值 + TTL 双条件，不只看次数）。
2. 系统技能 / 高频技能 / 新技能不被误归档；归档可逆（可恢复）。
3. dashboard 能看到被归档的技能 + 归档原因。
4. 不破坏现有 skill eval / evolve / draft 路径。

## 链接

| 文档 | 链接 |
|---|---|
| 来源 triage | [wiki-takeaway-triage-2026-06-20.md](../../../references/wiki-takeaway-triage-2026-06-20.md) 缺口③ + §5 |
| 能力图 ground truth | [skillforge-capability-audit-2026-06-20.md](../../../references/skillforge-capability-audit-2026-06-20.md) §4 |
| 复用模式参考 | `MemoryConsolidator`（Memory 生命周期管家四段） |
