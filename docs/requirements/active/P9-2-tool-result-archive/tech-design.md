# P9-2 技术方案

---
id: P9-2
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## TL;DR

新增 tool-result archive 表和 context replacement 路径，对单轮超大 tool result 做归档。实现位置应靠近 session context 构建，并保持原始消息历史语义。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 按单条消息聚合预算归档 | 痛点通常是某一轮 tool output 过大，而不是整个 session 总量。 | session-wide archive 不够精准。 |
| 2KB preview + archive ID | 保留可读上下文，同时显著减小 token 压力。 | 直接删除会丢失可追溯性。 |

## 架构

预计包含 `ToolResultArchiveEntity`、Repository、Service helper，以及 context-building 集成点。

## 后端改动

- 新增归档持久化。
- 识别超预算的 tool_result blocks。
- 生成 preview/reference replacement。
- 确保 context builder 能理解 archive reference。

## 前端改动

- MVP 不要求前端改动；如果现有消息展示需要更清楚展示 archive reference，再补 UI。

## 数据模型 / Migration

- 新增 `t_tool_result_archive`，包含 session/message 关联、content、preview、size、timestamps。

## 旧 ToDo 原文要点

| 子任务 | 说明 |
| --- | --- |
| P9-2 Per-message 聚合预算 + 归档持久化 | 单条 user message 的 tool_result 总量超 200K chars 时，按大小降序归档到 `t_tool_result_archive` 表，消息替换为 2KB preview + 引用 ID；可选新增 `ToolResultRetrieveSkill` 让模型按需读取。 |

## 错误处理 / 安全

- archive 写入失败应显式失败，不能静默丢 tool 内容。
- 不向客户端泄露内部路径或原始异常。

## 实施计划

- [ ] Full Pipeline plan review。
- [ ] 新增 schema/entity/repository。
- [ ] 新增 archive service。
- [ ] 接入 context message preparation。
- [ ] 补测试。

## 测试计划

- [ ] 阈值归档测试。
- [ ] preview replacement 测试。
- [ ] session reload 测试。
- [ ] compaction interaction 测试。

## 风险

- replacement 错误可能破坏 tool_use/tool_result 不变量。
- archive reference 需要跨 provider 稳定序列化。

## 评审记录

当前 P9-2 收窄任务尚未跑对抗评审。
