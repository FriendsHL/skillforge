# SYSTEM-AGENT-TYPING 系统 agent 类型区分 + 观察面板

---
id: SYSTEM-AGENT-TYPING
mode: full
status: delivered
priority: P1
risk: Mid
created: 2026-05-16
updated: 2026-05-17
delivered: 2026-05-17
phase1_commit: e659b0a
phase2_commit: pending
---

## 交付 ✅ 2026-05-17

**Phase 1** (commit `e659b0a`, 2026-05-17): agent_type 字段 + V89 migration + 5 Bootstrap idempotent self-heal + session-annotator user agent outcome 覆盖修复 (Hypothesis B starvation in `findRecentByLimit` createdAt DESC) + R1 3-tier annotator (user-first / system backfill / catch-all orphan) + W1 fix per-tier early dedup + FE Zod schema. 真活验证 50s 内 user agent outcome 0 → 9. mvn 1776/0/95. **飞轮 layer 1 root cause 真闭环**.

**Phase 2** (commit pending, 2026-05-17): F2 AgentList toggle + Tag + inline SystemAgentMonitorCard (cron / last_run / 7d trigger+output / Run Manually / View Sessions+Schedule) / F3 AgentDrawer system agent banner + readOnly + Delete disabled + fieldset disabled for behavior_rules/lifecycle_hooks (no Unlock per 2026-05-17 user simplify) / F5 BE GET /api/agents?agentType= filter + GET /api/system-agents/monitor 跨表聚合 endpoint + AgentService overload 不破 3 内部 callers / F6 Chat send gate Alert + double-guarded. **W2 mandatory r1 fix**: SessionList + Schedules 加 useSearchParams 读 ?agentId/?taskId filter consume (F4 partial 修完整). **W3 顺手 confirm**: fieldset disabled 兜底 behavior_rules + lifecycle_hooks editor. mvn 1797/0/95 + tsc + npm build 双绿 + 21 FE tests PASS (5 file) + 0 regression. Iron Law 核心 7+1 BE + 3 FE 0 diff.



## 摘要

V1-V5 累计交付了 5 个 system agent（memory-curator / session-annotator / metrics-collector / attribution-curator / user-simulator），跟用户自己创建的对话型 agent（Main Assistant / Design Agent / Research Agent / Session Analyzer / Code Agent）**混在同一张 t_agent 表 + 同一个 dashboard AgentList**。

用户痛点 3 条:
1. **混淆**：AgentList 一眼看不清哪些是"自己用的"哪些是"平台跑的"
2. **危险**：用户能编辑 / 删除 system agent，可能破坏 cron 跑的飞轮链路
3. **看不到**：system agent 跑产生的 session（origin='production' 由 cron 触发 / origin='user_sim' / 等）散落在多个 detail page，没集中观察入口

## 范围

### Phase 1 — 本次 PR（Full 档，~1-1.5d）

数据基建 + 飞轮原料覆盖 fix（用户 2026-05-17 strategic discussion 拍板"先解决飞轮 user agent 失败不进飞轮 = layer 1 root cause"）：

1. **数据模型加 `agent_type`**: `t_agent.agent_type` enum 'user' / 'system'，**V89** migration（V87 已被 V87__disable_canary_metrics_collector.sql 占）默认 'user' + 5 个已知 system agent 显式设 'system'
6. **session-annotator user agent 覆盖修复**（新加）: 当前 117 outcome 标注全在 system agent (attribution-curator 63 / metrics-collector 24 / session-annotator 29 / memory-curator 1)，user agent (Main Assistant 58 + Design Agent 23 + Research 15 + Code Agent 14 = 110 session) **0 个 outcome 标注**。BE-Dev 先 systematic-debugging Phase 1 取证（grep + SQL 确认 3 个 hypothesis 中哪个为真：A=DetectSignalAnnotations 排除 user agent / B=STEP 2 LLM cap=10 优先 system / C=user agent session signal 太弱），再修 session-annotator pipeline（system prompt + DetectSignalAnnotationsTool / SessionAnnotationSignalService 取决于取证）让 user agent session 也进 sessions_needing_llm 列表 + 真被 STEP 2 LLM 标 outcome。**agent_type 字段作为 dispatcher 决定优先级的 input（user agent 优先 STEP 2 LLM 标注），不是过滤器**

### Phase 2 — 后续 PR（独立小包，按需排期）

UX 增强（用户 2026-05-17 表态"不需要太自动化、人工参与优先"，UX 不是紧手）：

2. **FE AgentList 默认隐藏 system agent**：加 toggle "Show system agents" (默认 off)；显示时加 visual badge 区分
3. **System agent 编辑保护**：AgentDrawer 检测 agent_type='system' 时，只读关键字段 (name / model_id / system_prompt / tool_ids)，仅允许 enabled toggle + 监控
4. **System agent 不可发起 chat**：Chat page 检测 agent_type='system' 时禁 send button (admin override 可绕过)
5. **集中观察面板**：新建 `/insights/system-agents` 或 admin section 列 5 个 system agent + 各自最近 N 个 session + cron schedule + last run timestamp + 触发 manual run 按钮 (read-only observability)

## 不在范围内

- **不动数据**：不重命名 / 删除已存在 agent，仅加新字段
- **不破 V1-V5 现有路径**：bootstrap (V81/V79/V75/V85) 写入逻辑不动，只在 idempotent update 时补 agent_type='system'
- **不动 t_session_message / chat 路径** (Iron Law 核心 7+1)
- **不重写 AgentList**，只加 filter

## 阅读顺序

1. [MRD](mrd.md) — 痛点详述 + 用户场景
2. [PRD](prd.md) — Phase 1 范围 + 验收点 + UI 草图
3. [技术方案](tech-design.md) — 数据模型 + V87 migration + FE filter + 观察面板

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
