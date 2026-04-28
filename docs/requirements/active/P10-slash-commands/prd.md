# P10 PRD

---
id: P10
status: prd-ready
owner: youren
priority: P2
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

实现聊天斜杠命令 MVP：`/new`、`/compact`、`/clear`、`/model`。

## 目标

- 从输入框执行常见 chat/session 操作。
- 提供 fuzzy command completion。
- 避免斜杠命令被发送给 Agent。

## 非目标

- MVP 不做自定义命令注册。
- MVP 不做 `/help`。
- `/model` 不持久化修改 Agent 模型配置。

## 功能需求

- 前端拦截 `/` 前缀输入。
- 输入时展示命令补全。
- 回车执行命令，而不是发送 message。
- 后端通过 REST 或现有 endpoint 执行命令。

## 从旧 ToDo 合并的范围判断

- P10 从早期高优先级降为中后期节奏调节器。
- 用户价值修正为偏便利性：斜杠命令主要替代已有 UI 路径，不是新能力。
- 工期修正为 5-8 天，原因是 `/compact` 触碰 `CompactionService` 核心文件，必须 Full Pipeline。
- `/new` 对飞书移动端仍有独立价值，因为移动端没有完整 UI 阻断需求。
- `/model` 是 session 级临时切模型，不持久化到 agent 配置。

## 验收标准

- [ ] `/new` 创建新 session。
- [ ] `/compact` 触发 full compaction。
- [ ] `/clear` 清空当前显示的 conversation。
- [ ] `/model` 修改临时 session model。
- [ ] 未知命令返回可用的 UI 错误。

## 验证预期

- 前端 command parsing tests。
- 后端 command execution tests。
- 浏览器检查 command completion 和执行流程。
