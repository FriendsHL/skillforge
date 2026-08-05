# MRD — Agent Team 共享任务图

## 1. 背景

SkillForge 已支持 SubAgent、Team 和 Coordinator，并在
`AGENT-GOAL-AND-TOOL-RECOVERY` 中建设 Session 级持久化 Task、owner 与依赖。基础数据模型可以被 Team 复用，
但当前 owner 只是状态字段，没有 CAS claim、租约、心跳、自动派发、失败回收和不可变调度事件。

## 2. 用户与场景

- Team Lead：把复杂目标拆成可并行或有依赖的任务，并观察总体进展。
- Worker Agent：查看可领取任务、读取完整要求、领取、完成或报告阻塞。
- Runtime：在 Agent 完成、失败、超时、重启后维护任务状态和依赖一致性。
- 用户：查看 Team 当前做什么、谁在做、为什么阻塞，但不直接操作底层调度锁。

## 3. 产品目标

- G1：同一 Team/Root Task 的所有 Agent 看到一致任务图。
- G2：同一任务不能被多个 Agent 非预期重复领取。
- G3：依赖完成后自动解除后继任务阻塞。
- G4：Agent 终止或服务重启后任务可回收/恢复。
- G5：任务创建、领取、更新、完成都有审计来源。
- G6：Solo 与 Team 复用同一 Task 协议；Team 专属调度说明只在 Team context 渐进披露。

## 4. 非目标

- 不做真人团队项目管理、组织权限、Sprint 或工时系统。
- 不让 TaskCreate 自动启动 Agent。
- 不再新增与 `t_session_task` 平行的 TaskGraph 主表，除非进入 Active 后证明现有模型无法扩展。
- 不在当前 `AGENT-GOAL-AND-TOOL-RECOVERY` 中实现自动协调。
