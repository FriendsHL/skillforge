# P9-4/P9-5 MRD

---
id: P9-4-P9-5
status: mrd
source: user
created: 2026-04-28
updated: 2026-04-28
---

## 用户诉求

长 session 需要更好的 compaction 控制，以及 compact 后更可靠的上下文恢复。

## 背景

P9-5-lite 已经修复 pending FileWrite/FileEdit input 保留。完整 P9-5 仍需要明确“最近操作文件”的数据来源。

## 期望结果

partial compact 可以压缩 context 的头部或尾部；full compact 后可以恢复有价值的文件、skill、pending task 上下文。

## 约束

- P9-4 是 P9-5 的前置。
- P9-5 不重复 P9-5-lite。
- 实现前必须先设计 recent-file source。

## 未决问题

- [ ] 最近文件应该来自 trace spans、新的 file activity 表，还是其他来源？
