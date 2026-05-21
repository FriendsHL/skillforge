# ATTRIBUTION-DISPATCHER-AGENT — 飞轮调度子系统真接 cron

---
id: ATTRIBUTION-DISPATCHER-AGENT
mode: full
status: active
priority: P1
risk: Medium
created: 2026-05-21
updated: 2026-05-21
---

## User Request

> "走A1+B Full pipeline吧"

## 背景 / 问题

刚 trace `7c1aa5cc-ae9f-4de2-b53c-b7188a81aa3e` (attribution-curator @ 03:06 UTC) 暴露：

ScheduledTask id=6 `attribution-dispatcher-hourly` 配置成 **直接 fire `agent_id=9` (attribution-curator)** with generic prompt `"Attribution dispatcher: scan unprocessed patterns and propose optimizations"`，**未传 `patternId`**。

curator 看到无 patternId → 正确停下不写 OptEvent → 整个自动 attribution loop 空跑。

Java `AttributionDispatcherService.dispatchUnprocessedPatterns()` 真活工作（已通过 REST `/api/attribution/admin/trigger-dispatch` 在 MULTI-DIM-ATTRIBUTION dogfood 验证：OptEvent #116/117 生成 / curator processed / cooldown 设置 / 再触发 skippedCooldown=2 verified），**但 cron 路径跟它没接上**。

## Goals

- **G1**: cron `attribution-dispatcher-hourly` 真活触发 dispatcher 逻辑（filter pattern → per-pattern 调 curator with patternId），无需操作员手按 REST endpoint
- **G2**: dispatcher 与 attribution-curator **职责分离**——curator 专注单 pattern 归因（保留现 system prompt STEP 1-4），dispatcher 专注扫描 + 路由
- **G3**: dashboard SystemAgentMonitorCard 加 `attribution-dispatcher` 一行，让操作员能看 cron 健康（7d trigger / output / last_run_status / 手动 Run / 看 sessions）

## Out of Scope

- 不动 FE Insight Loop flowchart（dispatcher 是 plumbing 不该污染 9 步飞轮节点视图）
- 不动 dispatcher Java service 的 4 道 filter 逻辑（MULTI-DIM-ATTRIBUTION 已交付）
- 不动 curator system prompt（保留 STEP 1-4 不变）
- 不重构 `t_scheduled_task` schema 支持 "HTTP call" 模式
- 不重新加 `@Scheduled` 注解（避免 c3225e0 之后的 dual-schedule 噩梦复发）

## Implementation Decisions (前置拍板，Plan 时可调)

| 决策 | 当前倾向 | 备注 |
|---|---|---|
| 新 agent name | `attribution-dispatcher` | 跟 cron name 对齐；agent_id 让 DB serial 决定 |
| dispatcher system_prompt 长度 | 极短（1-3 句）| 「Call `DispatchAttributionPatterns` 一次，根据 tool 返回 summary 报数」+ JSON 输出格式 |
| 新 Tool 名 | `DispatchAttributionPatterns` | 类比 `RecomputeMetrics`/`RecomputeClusters`/`DetectSignalAnnotations` 命名 |
| Tool body | 调 `AttributionDispatcherService.dispatchUnprocessedPatterns()`，返 summary JSON | 复用 Java service，零业务逻辑漂移 |
| dispatcher → curator 的 fan-out 通道 | 复用 Java service 现 path（不引新 SubAgent fan-out from agent layer）| 即：dispatcher agent 调 Tool → Java service per-pattern fire curator session（跟 REST endpoint 当前行为一致）|
| Tool 暴露给谁 | 仅 dispatcher agent | 不给 curator / user agent（防误调）|
| Bootstrap 类 | 新建 `AttributionDispatcherBootstrap`（类比 `MemoryCuratorBootstrap`）| @ApplicationReadyEvent + 空 prompt 替换 + idempotent self-heal |
| Migration | V90（或下一可用号）：INSERT t_agent 行 + UPDATE t_scheduled_task #6 SET agent_id={新 id} + prompt_template 短句 | 不删 cron 行，update agent_id 即可 |
| FE 改动范围 | **仅 SystemAgentMonitorCard 自动多一行**（BE `/api/system-agents/monitor` 返多一个 entry，FE 列表已支持 dynamic iterate 就 0 改）；否则加 1 行映射 | 不加 flowchart 节点，不加 sidebar 入口 |
| Cron status | 创建后 enabled=false（保持当前观察期）；操作员手动 enable | 跟 memory-curator 同款保守上线 |

## Iron Law 自检

- ✅ 核心 7+1 BE 不触碰（新加 dispatcher agent + Tool，不动 AgentLoopEngine / ChatService 等核心引擎）
- 🟡 **新 migration V90 触发 Full pipeline 红灯**（schema 边界 — 加 t_agent 行 + 改 t_scheduled_task 行）
- ✅ 无 @Transactional 既有路径变动
- ✅ Jackson 契约 footgun #6 不触碰（不改 DTO；Tool 输出 schema 内部）
- ✅ persistence-shape-invariant 不触碰（不动 Message / ContentBlock）
- 🟡 触碰 Bootstrap 链 + Tool registry + SkillForgeConfig (3 处) → 走 java-design-reviewer

## 验证

- mvn -pl skillforge-server -am test 全绿（baseline 1918 + 新加 tool/bootstrap test）
- BE 重启后 V90 apply，`SELECT * FROM t_agent WHERE name='attribution-dispatcher'` 返 1 行 system 类型
- `SELECT * FROM t_scheduled_task WHERE id=6` 显示 agent_id = 新 id + 新 prompt_template
- 手动 trigger cron → 生成 dispatcher session trace（1 个 LLM 调 + DispatchAttributionPatterns tool 1 调）
- Tool 返 summary：`{candidates_scanned, dispatched, skippedSurface, skippedCooldown, skippedActive}`
- 若有 eligible pattern → 同 trace 时刻并行起多个 curator session（per-pattern 各 1 个）
- 若 0 eligible pattern → dispatcher 报 0 dispatched 干净结束
- dashboard `/system-agents` 显示 6 卡，attribution-dispatcher 卡能看 7d trigger / last_run 数据
- 真活 dogfood 验证：手按 cron #6 enable → 等下一小时 :15 触发 → curl `/api/optimization-events?limit=10` 看新 OptEvent 是否生成（前提 t_session_pattern 有未过 cooldown 的 eligible row）

## Pipeline

Full 档（schema 边界 + 新 agent + 新 Tool + 多模块）—— 走 Full 流程：

- TeamCreate `attribution-dispatcher-agent`
- **Plan phase enabled**（多个实施细节要拍：Tool 返回 schema / Bootstrap idempotency 策略 / Migration 升 agent_id 还是 cron rewrite / FE auto-extend or hardcode）
- BE-Dev Opus 单 dev（pure BE 改动占 90%）+ FE-Dev Opus（如 FE 需 0 改可砍）
- java-reviewer + database-reviewer + java-design-reviewer 三审（migration + 新 Service-like Tool + Bootstrap 抽象）
- Judge (主会话 Opus) 仲裁
- Phase Final mvn test + JVM restart + 真活 cron trigger 验证 + commit
