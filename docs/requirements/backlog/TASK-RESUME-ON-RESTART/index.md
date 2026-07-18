# TASK-RESUME-ON-RESTART — 任务重启与恢复

---
id: TASK-RESUME-ON-RESTART
mode: full
status: kill-recovery MVP scope proposed; implementation not started
priority: P1
risk: High
created: 2026-06-24
updated: 2026-07-19
source: 用户要求参考 OpenClaw，为 SkillForge 提供完整的任务重启/恢复方案
---

- 产品需求：[prd.md](prd.md)
- 技术设计：[tech-design.md](tech-design.md)
- 实施与验收：[rollout-plan.md](rollout-plan.md)
- 收窄首期：[kill-recovery-mvp.md](kill-recovery-mvp.md)

## 2026-07-19 范围收窄

用户明确首期只考虑：服务被 kill 时，数据库里仍为 `running` 的 task 在新进程启动后能否恢复。

结论是可以，但只能恢复“持久化信息足够且重放安全”的任务。首期不建设完整的多实例 lease、graceful drain、通用 task ledger 和跨进程迁移；采用单实例 startup reconciliation。完整方案保留为后续演进方向，首期实施以 [kill-recovery-mvp.md](kill-recovery-mvp.md) 为准。

## 一句话方案

SkillForge 不把“恢复”等同于重发一句 `Continue`，而是增加一个持久化的执行恢复账本：在 turn 被接受时原子记录 claim，在安全边界记录 checkpoint 和工具副作用回执，使用 lease 判断旧进程是否失联，由统一 Recovery Coordinator 在启动后做 `resume / reconcile / wait / fail-closed / tombstone` 决策。

## 对 OpenClaw 的参考结论

OpenClaw 当前方案中值得复用的不是具体 SQLite 实现，而是以下原则：

1. graceful restart 先停止接单并 drain；强制中断前标记恢复。
2. turn admission、恢复 claim 和原始请求关联必须持久化。
3. 启动时同时检查显式恢复标记与“仍 running 但没有活 owner”的孤儿任务。
4. 自动恢复有持久化次数预算、退避、稳定 dispatch ID 和 tombstone，不能无限循环。
5. Kill Recovery MVP 对孤立 tool_use 采用删除并从完整消息边界重跑，明确接受可能重复副作用；完整方案再通过 receipt 做精确判定。
6. main session、subagent、background task、cron、ACP 由明确的 lifecycle owner 分别处置。
7. 对无法安全续接的 transcript，明确要求用户重试，而不是制造“已恢复”的假象。

参考：

- [OpenClaw Restart recovery](https://docs.openclaw.ai/gateway/restart-recovery)
- [OpenClaw Gateway protocol / task ledger](https://github.com/openclaw/openclaw/blob/main/docs/gateway/protocol.md)

## SkillForge 现状结论

- 根 agent-loop：线程池内存态；持久化 user message 后执行，重启时当前通用 startup recovery 会将 `running`/`waiting_user` 改为 error。
- SubAgent：已有 `t_subagent_run` 和 startup recovery，但它晚于通用恢复执行，两套 owner 的决策存在冲突。
- Workflow：run/step/journal 已持久化，人工 approve 后可 replay；进程崩溃后的 startup reconciliation 尚未统一。
- ACP/cc/codex：外部进程与连接由客户端/进程 owner 管理，server 重启后无法承诺原进程原地续接。
- 定时任务：定义可恢复；一次 in-flight cron run 默认不补跑，除非未来为该任务显式选择 at-least-once 策略。

## 已锁定设计决策

| 决策 | 结论 |
| --- | --- |
| 恢复精度 | 最近一个持久化安全边界，不承诺指令级/mid-tool 精确续点 |
| MVP 副作用策略 | 删除孤立 tool_use 并重新唤醒 task；不新增 Tool Receipt，接受 at-least-once 与重复副作用风险 |
| waiting_user | 恢复 registry/card，保持等待，不自动发起新的 agent turn |
| 根 turn 恢复 | 从原始 source turn 继续，不追加伪造 user Query；恢复 directive 存 claim 并仅注入 engine 的防御性 context copy |
| 多实例 | 从第一版就使用 owner instance + epoch + lease + CAS claim，不能依赖单机 startup 假设 |
| 重试上限 | 每个中断周期最多 3 次持久化 charged attempts；指数退避；耗尽后 tombstone |
| 过期窗口 | main turn 默认 2 小时；超过窗口不自动复活，转为 interrupted，需要用户确认 |
| startup owner | 用一个 Recovery Coordinator 替代相互竞争的 startup handlers；按 task kind 路由策略 |
| ACP | server-owned record 可对账；外部 runtime 默认 client-owned，断联后标 interrupted，不伪装成真续点 |
| cron | 本期保持“下次 schedule 再运行”；需要补跑的任务以后显式配置 misfire policy |

## 完整方案后续分期

1. Phase 0：schema、统一状态模型、shadow reconciliation 和指标，不自动恢复。
2. Phase 1：根 agent-loop 的安全恢复；覆盖 read-only、安全 checkpoint、waiting_user、tombstone。
3. Phase 2：工具执行回执与副作用屏障、graceful drain、outbound delivery reconciliation。
4. Phase 3：SubAgent 接入统一 coordinator，移除 startup owner 冲突。
5. Phase 4：Workflow crash replay；利用现有 journal，新增 definition hash/lease/attempt gate。
6. Phase 5：ACP client-owned interruption UX 与可选 restart-from-prompt；不承诺进程原地重连。

每个 Phase 都是独立 Full increment；上一个阶段真重启 E2E 通过后才能放量下一个阶段。

## 本需求的完成定义

- 文档方案获用户批准后才进入实现。
- “实现完成”必须至少包含根 turn、SubAgent、Workflow 的处置矩阵和运维可见性；ACP 可以明确以 interrupted/restart-from-prompt 收口。
- 不以普通单元测试代替真进程 kill/restart、数据库核对和副作用不重复证明。
