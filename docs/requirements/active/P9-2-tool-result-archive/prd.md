# P9-2 PRD

---
id: P9-2
status: prd-ready
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

当单条 user message 中 tool_result 总量超过 200K chars 时，按大小降序归档最大的 tool results，并在 context 中替换为 2KB preview + archive reference。

## 目标

- 减少大 tool output 对活跃 context 的挤占。
- 保持 append-only message history 语义。
- 归档内容可通过 ID 追溯。

## 非目标

- 本任务不做完整 post-compact 文件摘要注入。
- 本任务不做 partial compact。
- 本任务不大改 session message model。

## 功能需求

- 检测单条消息 tool result aggregate size 是否超过 200K chars。
- 按大小降序归档 tool result，直到消息回到预算内。
- 用 preview 和 archive reference 替换被归档内容。
- 在专用 store/table 中持久化归档正文。

## 旧 ToDo 原文要点

> 直接解决“长对话 context 爆满”真实用户慢性病。P6 消息行存储上线后，归档表前置条件已满足，工程复杂度大幅降低。触碰核心文件（CompactionService），走 Full Pipeline 对抗循环。

| 子任务 | 说明 |
| --- | --- |
| P9-2 Per-message 聚合预算 + 归档持久化 | 单条 user message 的 tool_result 总量超 200K chars 时，按大小降序归档到 `t_tool_result_archive` 表，消息替换为 2KB preview + 引用 ID；可选新增 `ToolResultRetrieveSkill` 让模型按需读取。 |

## 验收标准

- [ ] 超大 tool result 会从活跃 context 中归档出去。
- [ ] context 里保留 preview 和 archive ID，不保留全文。
- [ ] 普通小 tool result 不受影响。
- [ ] compaction 和 session reload 路径能保留 archive reference。

## 验证预期

- 后端：archive 创建和 context replacement 的 service/repository 测试。
- 数据库：archive table migration 校验。
- 回归：session context construction 和 compaction 测试。
