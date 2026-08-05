# AGENT-TEAM-TASK-GRAPH — Agent Team 共享任务图

> 状态：Backlog / Full
> 创建：2026-08-05
> 触发：Session Task 与完整 Team 自动协调职责边界澄清

## 摘要

为 SkillForge 的 Team/Coordinator 在现有持久化 Session Task 之上增加跨 Agent 领取、租约、心跳、自动派发、
失败回收和不可变事件审计。它不新增第二套 Tool Schema 或任务表，而是把已有 owner/依赖字段升级为真正的
多 Agent 调度语义。

## 核心边界

```text
Main Agent / Solo Session
  TaskCreate / TaskGet / TaskUpdate / TaskList
  -> Session 内持久化计划，增量更新

Agent Team / Coordinator
  同一 Task 协议 + Team context
  -> claim / lease / heartbeat / dispatch / recovery / audit events
```

TaskCreate 只创建工作项，不负责启动 Agent。Agent/Team 调度器负责派发，TaskUpdate 负责 owner、状态和依赖。

## 进入 Active 的触发条件

满足以下任一：

1. Team Coordinator 开始承担真实多 Agent 生产任务。
2. 两个以上 Agent 需要并发领取同一 Root Task 的工作项。
3. SubAgent 结果恢复需要持久化未完成依赖图。
4. 现有 Session Task 的 owner/依赖不足以表达租约、自动派发或跨 Agent 恢复。

## 文档

1. [MRD](mrd.md)
2. [PRD](prd.md)
3. [技术设计草案](tech-design.md)
