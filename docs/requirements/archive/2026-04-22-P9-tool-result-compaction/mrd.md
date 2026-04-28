# P9 MRD

---
id: P9
status: done
source: historical-backfill
created: 2026-04-22
updated: 2026-04-29
---

## 用户诉求

历史补录：长 session 中 tool 输出会快速占满 context，需要更精细的裁剪、压缩和清理能力。

## 背景

Claude Code 等工具会对工具输出做压缩和上下文管理。SkillForge 需要类似能力，避免 tool_result 持续污染 context。

## 期望结果

建立 compactable 工具白名单、冷清理、session memory compact 等基础能力，并为后续 P9-2/P9-4/P9-5 打基础。

## 约束

- 不破坏 tool_use/tool_result 协议。
- 保留必要 preview 和可追溯信息。

## 未决问题

- P9-2、P9-4/P9-5 已拆为 active 需求继续推进。
