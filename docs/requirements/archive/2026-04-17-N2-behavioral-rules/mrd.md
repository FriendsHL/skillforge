# N2 MRD

---
id: N2
status: done
source: historical-backfill
created: 2026-04-17
updated: 2026-04-29
---

## 用户诉求

历史补录：需要可配置的 Agent 行为规范层，让不同 Agent 能遵循不同规则和风格。

## 背景

仅靠 system prompt 难以结构化管理行为约束。需要内置规则、预设和自定义规则，并能安全注入 prompt。

## 期望结果

用户可以在 Agent 配置中启用内置规则、添加自定义规则，并让规则稳定注入 SystemPromptBuilder。

## 约束

- 自定义规则需要防 prompt injection。
- YAML round-trip 需要兼容 corrupt data。

## 未决问题

- 无。需求已交付。
