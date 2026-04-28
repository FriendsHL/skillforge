# P9-2 Tool Result 归档

---
id: P9-2
mode: full
status: prd-ready
priority: P1
risk: Full
created: 2026-04-28
updated: 2026-04-28
---

## 摘要

归档超大的单轮 tool result，避免长 session 持续把巨量工具输出带进活跃 LLM context。

## 阅读顺序

1. [MRD](mrd.md)
2. [PRD](prd.md)
3. [技术方案](tech-design.md)

## 当前状态

排在 Sprint 3。触碰 compaction / session context 行为和持久化，必须走 Full Pipeline。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 相关历史方案 | [P9 归档方案](../../../requirements/archive/2026-04-22-P9-tool-result-compaction/tech-design.md) |
