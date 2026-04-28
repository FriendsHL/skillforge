# Session State PRD

---
id: SESSION-STATE
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-17
updated: 2026-04-29
---

## 摘要

实现 session 状态感知、交互确认和执行模式基础设计。

## 目标

- 定义执行模式。
- 定义用户确认原则。
- 定义运行时状态模型。
- 改造后端和前端状态展示。

## 非目标

- 不在本需求中实现所有 future ask-mode 变体。

## 功能需求

- Session 状态模型。
- 后端状态更新。
- 前端状态展示。
- 用户交互原则。

## 验收标准

- [x] session 状态可被用户理解。
- [x] ask/auto 等执行模式有明确语义。

## 验证预期

- 后端状态流测试。
- 前端交互检查。
