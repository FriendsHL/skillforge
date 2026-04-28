# P9 PRD

---
id: P9
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-22
updated: 2026-04-29
---

## 摘要

P9 历史需求覆盖 tool 输出裁剪、白名单、冷清理和零 LLM session memory compact。

## 目标

- 建立 CompactableToolRegistry。
- LightCompactStrategy 过滤白名单。
- Time-based cold cleanup。
- SessionMemoryCompactStrategy。
- 为后续 tool result archive 和 partial compact 留出方向。

## 非目标

- P9 历史需求不再直接承载 P9-2/P9-4/P9-5 实现。

## 功能需求

- P9-1 Compactable 工具白名单。
- P9-3 Time-based 冷清理。
- P9-6 Session Memory 零 LLM 压缩。

## 验收标准

- [x] P9-1 测试通过。
- [x] P9-3 测试通过。
- [x] P9-6 测试通过。

## 验证预期

- 相关 compact / engine tests。
