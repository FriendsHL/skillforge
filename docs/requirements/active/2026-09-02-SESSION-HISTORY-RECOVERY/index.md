# SESSION-HISTORY-RECOVERY — Compact 后的历史事实恢复与 Session 连续性

> 状态：Full / 已实现并完成专项真实浏览器 E2E；两项 durable 问题暂停，Full/release gate 仍开放（见 review-2026-09-09.md）
> 日期：2026-09-10
> 优先级：P0

## 摘要

SkillForge 已持久化完整 Session 消息、Compact summary 范围、Tool 结果归档、checkpoint、Task 与
Tool/Skill runtime snapshot。本需求不重建另一套事实库，而是把现有数据组织成模型可按需使用的恢复闭环：

```text
确定性 compact-checkpoint envelope
+ 原始 active summary（正文逐字不变）
+ summary 未覆盖的已持久化尾部
                 |
                 +-- 信息充分 ----------------------> 继续任务
                 |
                 +-- 精确事实缺失、连续性断裂或用户指出偏移
                                |
                       SessionHistorySearch
                                |
                       SessionHistoryRead
                                |
                    从原始证据恢复当前需要的事实
```

History 恢复的是“当前任务此刻需要的事实”，不是把整段对话重新灌回上下文，也不是自动回滚时间线。

## 已冻结的产品判断

1. 原始 `t_session_message` 与经过 occurrence 校验的 `t_tool_result_archive` 是历史事实源；summary 是有损导航投影，不能覆盖原始证据。
2. Compact 后的正常工作视图是“envelope + 原始 summary + 未覆盖的已持久化尾部”；正确性不依赖恰好保留 20 条消息。
3. `SessionHistorySearch` 与 `SessionHistoryRead` 是系统常驻工具。总开关开启时，所有现有和未来 Agent 都同时获得它们，不依赖 Agent `tool_ids`，也不做 grant migration。
4. History 只能读取 Harness 固定注入的当前 `sessionId/userId`。模型 Schema 不暴露身份字段且 `additionalProperties:false`；跨 Session 发现与选择继续由 SessionList/Session 域负责。
5. History cursor 是 unsigned、opaque、versioned 的精确回传 token，不是权限凭证。首版不做 HMAC；每次读取仍重新校验当前用户、Session、epoch、selector、projection version 与 snapshot cutoff。
6. Agent 先用 summary/tail；确有缺口时 Search 定位、Read 精确展开。用户仅指出“偏了”不触发 checkpoint restore。
7. 每个 Tool step 采用两阶段持久化：先提交 assistant `TOOL_USE` intent 并取得 ACK，再 claim/执行；完整、原序的全部 `TOOL_RESULT` 在一个事务中闭合后，才允许下一次 LLM、queued-user drain 或 Compact。
8. 崩溃后的自动重放仅适用于服务端声明的 `READ_ONLY_REPLAYABLE` 或真正传播稳定幂等键的 Tool。未知/有副作用 Tool 进入 `UNCERTAIN_PENDING_RESOLUTION`，先通过 generation/fence 使旧执行者失效，再等待 owner 或显式授权 admin 审计确认。
9. `UNCERTAIN_PENDING_RESOLUTION` 不是终态。唯一出口是 `ACKNOWLEDGE_UNKNOWN_OUTCOME`：原子写入一组原序 `OUTCOME_UNKNOWN` results、`RESOLVED_UNKNOWN` 状态、inbox disposition 与只追加 audit；不得宣称成功或失败，也不得自动重试。
10. History 永久排除自身 Search/Read 的 tool blocks；同一原始 TOOL_RESULT occurrence 与有效 archive 只暴露一个 canonical 候选；默认排序始终是 ORIGINAL 证据先于 DERIVED_SUMMARY。
11. `ContextRuntimeSnapshot` 是无正文的 Tool/Skill 控制面引用。resume 读取当前状态，branch 复制 checkpoint 状态，restore 覆盖当前状态；它不承担历史事实恢复。
12. 生产首版不实现 `RecoveryManifest/Attachment` 或一次性 provider recovery 状态。R0/R1/R2 只作为评测变量；Task 与当前文件仍分别以 TaskList/数据库和 FileRead/工作区为权威。

## 实施范围与硬门

| Batch | 范围 | 前置/退出门 |
| --- | --- | --- |
| 0 | 六份需求文档同步、r6 映射、独立 Full review | `BATCH0_ACCEPTED`；未通过时禁止迁移、实体、测试和业务代码 |
| 1 | flags、trust、V196–V198、实体/codec、closed schema | PostgreSQL 角色/trigger 的 audit 只追加策略必须冻结并验证 |
| 2 | intent-before-effect、execution generation/fence、结果原子闭合 | crash/ACK-loss/双实例/旧执行者拒绝矩阵通过 |
| 3 | interactive 全响应预检、ordered inbox、unknown-outcome resolution | CONTINUE 仅接受一个 durable Session run claim 与一个逻辑 transcript continuation |
| 4 | Compact envelope、pre-intent frontier、epoch、runtime checkpoint | 五类 Full 来源、连续 Compact、branch/restore 通过 |
| 5 | History Search/Read、selector/cursor/ref/projection/order | 当前 Session、self-exclusion、Unicode、legacy fail-closed 通过 |
| 6 | occurrence-owned canonical archive | raw/archive 单候选、相同 ID 不同 payload、100K 压力通过 |
| 7 | Dashboard 低信任结果与不确定结果确认 UI | 真实浏览器覆盖 live/replay/restore/双击与越权 |
| 8 | S1–S25、A/B/C/D × R0/R1/R2、灰度与回滚 | 全部质量、安全、token、wire、性能门通过 |

任何 Batch 0 后的协议修改都会使 `BATCH0_ACCEPTED` 失效并返回复审。

Batch 0 的独立 reviewer 只记录候选状态；root/Judge 在吸收 warning、冻结最终文档 hash 并确认没有提前开始代码后，才在
[Batch 0 验收记录](batch0-acceptance.md)中记录最终 `BATCH0_ACCEPTED`。

## 明确不做

- 不自动恢复、回放或把整段对话重新注入模型。
- 不新建第二份权威事实库，也不新增 LLM 驱动的 `FactRecover` 第三工具。
- 不默认跨 Session 搜索，不允许模型通过 JSON 选择任意 Session。
- 不把 History 输出、summary 或 Recovery 数据提升为系统指令。
- 不把 seq 当 ref identity；restore 后通过 `history_epoch` 防止同 seq 不同内容的 ABA。
- 不在首版给 cursor 加 HMAC，也不承诺修改 token 后仍能正确分页。
- 不把旧 `recovery_payload` 文件头或生产 Recovery Attachment 注入每轮请求。
- 不自动重放未知外部副作用；数据库与任意外部系统之间的严格原子性不作虚假承诺。
- 不把 Compaction checkpoint 当作运行中 Agent/Workflow 的通用进程重放协议；相关边界仍由 `TASK-RESUME-ON-RESTART` 负责。

## 关联需求

- [Agent Context Governance](../2026-07-26-AGENT-CONTEXT-GOVERNANCE/index.md)：Tool/Skill runtime、低信任上下文与 Compact continuity。
- [Agent Goal and Tool Recovery](../2026-08-05-AGENT-GOAL-AND-TOOL-RECOVERY/index.md)：Task 是任务状态权威，Compact 后从数据库重新注入。
- [Compact Idempotency Boundary Fix](../../archive/2026-07-14-COMPACT-IDEMPOTENCY-BOUNDARY-FIX/storage-redesign.md)：range summary、checkpoint 与派生模型视图。
- [Task Resume on Restart](../../backlog/TASK-RESUME-ON-RESTART/index.md)：运行中任务的进程级恢复边界。

## 文档

1. [调研与现状审计](research-report.md)
2. [MRD](mrd.md)
3. [PRD 与 S1–S25](prd.md)
4. [技术设计](tech-design.md)
5. [交付计划](delivery-plan.md)

## 精确事实恢复专项复审

[2026-09-09 Review 修复与验证](review-2026-09-09.md)：针对 Search/Read、Compact 派生内容、旧会话接入和开关组合；完整发布验收门仍开放。
