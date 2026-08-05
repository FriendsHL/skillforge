# MRD — 持久化 Task、目标连续性与 Artifact 恢复

## 1. 背景

SkillForge 已能完成长任务、调用工具、发布 Personal App 并跨多轮继续对话，但当前计划由内存态 Todo 列表
承载。它缺少稳定 ID、增量更新、可靠重启恢复和多端统一查询能力。用户对已交付结果追加修改时，旧完成列表
还可能继续注入模型；Artifact custom 模式失败后，模型又容易把“修发布工具”误当成用户的业务目标。

问题 Session 表明这不是单一模型质量问题，而是计划协议、持久化、动态上下文和工具错误契约共同造成的。

## 2. 用户问题

1. 我只是继续追问，为什么 Agent 会重写计划或丢掉原目标？
2. 我指出结果不满足要求后，为什么旧任务仍显示完成？
3. 服务重启或 Compact 后，当前执行进度为什么不能可靠恢复？
4. Artifact 发布失败，为什么 Agent 开始围绕工具报错反复尝试并发生需求漂移？
5. Dashboard 和 iOS 为什么看不到一致的总体进度、当前动作和阻塞状态？

## 3. 产品目标

- G1：以一个持久化 Task 协议替代两套重叠的 Todo/Task 协议。
- G2：普通追问、修改当前项、追加新项、切换优先级和重开失败交付均有确定行为。
- G3：Task 在 Compact、进程终止和服务重启后可查询、可继续。
- G4：Tool failure 只作为执行事实，不自动成为用户目标或业务 Task。
- G5：Artifact 自定义页面是一级能力，模板只是可选快速路径。
- G6：Dashboard 与 iOS 使用同一 DTO 展示可解释的任务进度。
- G7：真实 Session 回放能稳定捕获需求漂移和工具失败污染。

## 4. 成功指标

- Main Agent 仅看到 Task 四工具，不再看到 TodoWrite。
- Task 状态重启后保持，依赖和 owner 约束没有脏写或循环。
- 普通问题追加不创建/更新 Task；真正范围变化按行为矩阵增量更新。
- 全部 completed/deleted 时不注入 Task Reminder；未完成任务可在 Compact 后重新注入。
- Artifact 错误包含可机器判断的错误码和修复动作，不回传大段 HTML/base64。
- Dashboard/iOS 加载 Task 失败时 Chat 仍可进入，历史 TodoWrite 消息仍能查看。
- 脱敏回放覆盖 PRD 的十个关键场景并形成自动回归。

## 5. 非目标

- 不构建通用项目管理系统或本轮就启用完整 Team 调度。
- 不为每种内容新增固定 Artifact 模板。
- 不自动把所有用户消息转成任务。
- 不新增 LLM judge、额外模型调用或通用完成证据框架。
- 不在本包实现 Tool 熔断、Schedule 真运行验收或 Skill-backed Schedule。
