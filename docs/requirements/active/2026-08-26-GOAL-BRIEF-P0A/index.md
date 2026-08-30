# GOAL-BRIEF-P0A 对话内轻量目标确认

---
id: GOAL-BRIEF-P0A
mode: full
status: implemented
priority: P1
risk: Full
created: 2026-08-26
updated: 2026-08-30
parent: ../2026-08-26-GOAL-ALIGNED-AGENT-FACTORY/
---

## 摘要

主 Agent 对长期、多步骤、新能力或高风险任务，在现有 Session Task metadata 中记录非权威 Goal Brief，并在 Chat 中显示紧凑目标卡。用户通过普通消息确认、修改或选择只做一次；普通问答不受影响。

## 边界

- 不新增表，不修改 Agent Loop、Message JSON、Eval 或 AgentEntity。
- Goal Brief 是模型提议，不是权限、外部代码执行或生产晋级授权。
- 用户按钮产生普通用户消息，不新增隐藏审批通道。

## 阅读顺序

1. [PRD](prd.md)
2. [技术方案](tech-design.md)

## 当前状态

P0a 的 DTO、Task 生命周期、目标卡和交互已完成实现、自动化验证和浏览器验收。首次非 Coding dogfood 证明 V193 的“提出 Goal Brief”措辞可能被模型理解为可选，V194 因此将命中条件改为必须创建并等待反馈；第二次 dogfood 发现模型会发明 `DRAFT` 和扩展 metadata，V195 因此补充严格形状与 completed 自检。最终验收覆盖简单问答无卡、Coding 与非 Coding 长任务有效卡、三个普通消息动作、Session 切换和刷新恢复；高影响操作仍使用原有独立确认链。
