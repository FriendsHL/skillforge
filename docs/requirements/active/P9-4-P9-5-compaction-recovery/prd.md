# P9-4/P9-5 PRD

---
id: P9-4-P9-5
status: prd-draft
owner: youren
priority: P2
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

支持 partial compaction 和 compact 后上下文恢复；但必须先决定 recent file activity 的来源。

## 目标

- 增加 `partial_head` 和 `partial_tail` compaction 模式。
- full compaction 后注入最近文件摘要。
- 是否恢复 active skill context 和 pending tasks，需要先确认价值。

## 非目标

- 不重复 pending FileWrite/FileEdit input 保留。
- 最近文件来源未定前不进入实现。

## 功能需求

- 扩展 compaction tool/API 支持 partial modes。
- 定义最近文件数据来源。
- 增加有预算上限的 post-compact recovery payload。

## 从旧 ToDo 合并的原始范围

| 子任务 | 范围 |
| --- | --- |
| P9-4 Partial compact 支持 | `FullCompactStrategy` 新增 `compactUpTo`（压缩头保留尾）和 `compactFrom`（压缩尾保留头）；`ContextCompactTool` 扩展 `level=partial_head/partial_tail`。 |
| P9-5 Post-compact 上下文恢复 | Full compact 后自动注入最近操作的文件摘要，预算为 5 个文件 / 50K token；活跃 skill 上下文和 pending tasks 保留为待评估部分。 |
| 已完成切片 | pending FileWrite/FileEdit input 保留已由 P9-5-lite 前置完成，本需求不要重复实现。 |

## 验收标准

- [ ] `partial_head` 压缩头部并保留尾部上下文。
- [ ] `partial_tail` 压缩尾部并保留头部上下文。
- [ ] post-compact recovery 最多注入 5 个文件摘要，总预算 50K token。

## 验证预期

- 后端 compaction tests。
- 真实长 session sanity check。
