# P9-2 MRD

---
id: P9-2
status: mrd
source: user
created: 2026-04-28
updated: 2026-04-28
---

## 用户诉求

长任务 session 即使工具产出非常大的结果，也应该继续可用。模型不应该在后续每一轮都反复携带巨大 tool result。

## 背景

P6 消息行存储已经上线，使得归档大 tool result 时不需要破坏原始消息结构。当前痛点是真实长 session 中 context 慢性膨胀。

## 期望结果

超大 tool result 在活跃 context 中被替换为小 preview 和稳定 archive reference。

## 约束

- preview 要足够让模型和用户理解发生了什么。
- 归档内容未来应可按 ID 取回。
- 不重复 P9-5-lite 已完成的 pending FileWrite/FileEdit input 保留工作。

## 未决问题

- [ ] 确认 `ToolResultRetrieveSkill` 是否放进首个 PR，还是作为 follow-up。
