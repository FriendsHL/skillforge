# P6 PRD

---
id: P6
status: done
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-21
updated: 2026-04-28
---

## 摘要

引入 `t_session_message` 消息行存储和结构化 compact boundary，让完整历史、summary 和 active context 可以共存。

## 目标

- 消息只增不删。
- 支持 full history、context messages、append messages 三种视图。
- compaction 写入 boundary 和 summary 行，不重写全部历史。
- 前端能识别 compact boundary。

## 非目标

- P6 不完整实现所有 branch/restore UI 能力。
- 不在本需求中解决全部 tool result 归档问题。

## 功能需求

- 新建 `t_session_message`。
- SessionService 提供完整历史、上下文构建和 append 接口。
- CompactionService 使用结构化 boundary/summary 行。
- ChatService 切换到消息行上下文构建。
- 前端适配 compact boundary 渲染。

## 验收标准

- [x] 旧 session 数据可回填。
- [x] compact 后旧消息仍可追溯。
- [x] context 构建只取 youngGen + summary + 新消息。
- [x] 前端能展示 compact boundary。

## 验证预期

- 后端 repository/service/compaction 测试。
- Flyway migration 校验。
- 灰度与回滚手册检查。
