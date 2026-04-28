# P9-4/P9-5 技术方案

---
id: P9-4-P9-5
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## TL;DR

在 recent-file source 决策前，本技术方案故意保持未完成状态。

旧 ToDo 明确要求：P9-5 必须先写 design doc 明确“最近操作文件”的数据来源，否则工期不可估。P9-4 是 P9-5 的硬前置。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| P9-4 先于 P9-5 | P9-5 依赖 partial compaction 机制。 | 两者一起做会增加核心 compaction 风险。 |

## 架构

等 recent-file source 决策后再确定。

## 后端改动

- 扩展 `FullCompactStrategy` 或 compaction orchestration，支持 partial range。
- 扩展 `ContextCompactTool`，支持 `level=partial_head/partial_tail`。

## 待决策：最近操作文件来源

旧 ToDo 只明确了两个候选方向：TraceSpan 查，或新表。

## 前端改动

- MVP 不一定需要前端；除非要在 UI 暴露 partial compaction 控件。

## 数据模型 / Migration

- 取决于 recent-file source 决策。

## 错误处理 / 安全

- 保持 tool_use/tool_result 不变量。
- 避免注入过期或误导性文件摘要。

## 实施计划

- [ ] 决定 recent-file source。
- [ ] 设计 partial compact boundary。
- [ ] Full Pipeline review。

## 测试计划

- [ ] partial head/tail tests。
- [ ] post-compact recovery budget tests。

## 风险

- 错误边界切割会污染 context。
- 文件摘要来源不可靠会误导模型。
- 活跃 skill 上下文 + pending tasks 注入部分价值存疑，需 design doc 先行。

## 评审记录

实现前需要设计评审。
