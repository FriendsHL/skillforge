# PROD-LABEL-CLUSTER PRD

---
id: PROD-LABEL-CLUSTER
status: ratified
owner: youren
priority: P1
risk: Mid
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-14
updated: 2026-05-14
---

## 摘要

V1 飞轮起点：production session 自动标注（signal + LLM agent 双通道）+ 跨 session 简单 bucket 聚类 + dashboard pattern 浏览。

## 已 Ratify 决策

1. **本期 surface 范围**：架构层面预留 skill / prompt / behavior_rule / tool / hook / mcp 共 6 类 `SurfaceType` enum；V1 标注只输出 skill / prompt / behavior_rule / other / unclear 5 个值（其他 V4+ 接）
2. **双通道标注**：signal-based（trace/span 派生）+ llm-based（session-annotator agent）。**人工修标推到 V3**
3. **聚类深度**：简单 bucket on `(outcome × suspect_surface × top_failing_tool × agent_id)`。不上 ML / embedding
4. **不动核心路径**：SessionEntity / ChatService / SessionService / CompactionService / AgentLoopEngine 任何字段、任何方法签名都不改。新表通过 `session_id` 外键关联
5. **标注用 agent，不用单 LLM call**：`session-annotator` agent 走 `SubAgentDispatch` 派发，参考 `memory-curator` 模板（MemoryCuratorBootstrap + 4 tool 模板）
6. **架构预埋扩展位**：V1 落地 `SurfaceType` enum + `OptimizableSurface<V>` 空接口骨架，V2/V3/V4 加 surface 不改主框架

## 用户流程

1. 用户正常使用 SkillForge agent / skill（产生 production session）
2. 后台 **1 个 hourly ScheduledTask** 触发 `session-annotator` agent 跑一次（V69 memory-curator dogfood 同款 pattern）
3. agent 内部按 system prompt 顺序调 3 个 tool：
   - **`DetectSignalAnnotations(window=1h)`**：调 `TraceScenarioImportService.detectReasons` 写 source=signal 标注，返回需 LLM 标注的 session 列表（最多 10 条）
   - **`AnnotateSession(sessionId)`**（每条 session 一次）：agent 内 LLM 判断 outcome + suspect_surface，写 source=llm 标注
   - **`RecomputeClusters(window=7d)`**：按 `(outcome × suspect_surface × top_failing_tool × agent_id)` bucket 聚类，≥ 3 member 入 `t_session_pattern` + `t_pattern_session_member`
4. 用户进 dashboard `/insights/patterns` 看 pattern 列表 → 点开看 member → 跳现有 trace 详情页
5. 用户可在 dashboard `/schedules` 页关掉 `session-annotator-hourly` 或调频（P12 ScheduledTask 内建能力）

## 功能需求

### 标注层 — `t_session_annotation`

每条记录 = (sessionId, annotationType, annotationValue, source, confidence, createdAt)

- `annotationType`：枚举值
  - 来自 signal：`agent_error` / `tool_failure` / `span_error` / `high_token` / `multi_turn` / `has_tool_calls`
  - 来自 llm：`outcome` / `suspect_surface` / `top_failing_tool`（可选）
- `annotationValue`：
  - signal 类型固定 `"true"`（标签存在即表示该 reason 命中）
  - `outcome`：`success` / `partial_success` / `failure` / `cancelled`
  - `suspect_surface`：`skill` / `prompt` / `behavior_rule` / `other` / `unclear`
- `source`：`signal` / `llm` / `human`（V1 不写 human，但表预留）
- `confidence`：0..1，signal 固定 1.0；llm 由 agent 输出（< 0.5 不入聚类）
- 同 sessionId × annotationType 多个 label 允许（如同时 `agent_error` + `tool_failure`），但同 source 内幂等（重跑不重复写）

### 聚类层 — `t_session_pattern` + `t_pattern_session_member`

- `t_session_pattern`：
  - `signature` VARCHAR（cluster key 拼接，可读，例如 `failure|skill|FileWriteTool|code-agent`）
  - `outcome` / `suspect_surface` / `top_failing_tool` / `agent_id` 拆字段冗余（便于 filter）
  - `member_count`
  - `first_seen_at` / `last_seen_at`
  - `suggested_surface`：直接复用 `suspect_surface`（V3 attribution 才动）
- `t_pattern_session_member`：(pattern_id, session_id) 多对多
- 重跑幂等：同 signature 已存在则 upsert + 增量 add member

### Dashboard `/insights/patterns`

- Pattern 列表（默认按 member_count desc）
  - 列：signature / outcome chip / suspect_surface chip / member_count / first_seen / last_seen
  - filter：outcome / suspect_surface / agent
- 点开 pattern → drawer 展示：
  - 完整 signature + 时间窗口
  - Member session 列表（sessionId / agentName / completedAt / runtimeError 摘要）
  - 单条 member 点击 → 跳 `/traces?sessionId=...` 看 trace 详情（**复用现有 Traces.tsx**）

### Cron 触发

- **1 个 P12 ScheduledTask**：`session-annotator-hourly`，每小时整点跑
  - target_agent = `session-annotator`
  - `concurrency_policy = 'skip-if-running'`（内建防重入，**不需要 advisory lock**）
  - 默认 enabled = TRUE（dogfood 阶段直接跑；用户随时可在 dashboard `/schedules` 关 / 调频）
  - 跟 V69 memory-curator daily 04:30 cron 不冲突（不同时段）
- agent 内部按 system prompt 顺序调 3 tool（DetectSignalAnnotations → AnnotateSession × N → RecomputeClusters）
- **不写 Spring @Scheduled** —— 用项目自己的 P12 ScheduledTask 框架

## 非目标

- 不接 attribution agent / 不生成 candidate / 不起 A/B（V3+）
- 不接灰度 / canary（V2）
- 不做人工标签修正 UI（V3）
- 不做实时标注（hourly batch 够用）
- 不做 ML 聚类 / embedding
- 不做 multi-tenant 隔离
- 不改 SessionEntity / 不改 trace/span schema
- 不改 SmartImport / EVAL-V2 现有流程

## 验收标准

- [ ] 三张新表 + Entity / Repository 落地（带 IT 测试）
- [ ] `session-annotator` agent 跑通：派一次 dispatch 能拿到 outcome + suspect_surface
- [ ] Signal stage 输出与 `TraceScenarioImportService` 现有 6 reason 一致（同输入同输出，单元测试）
- [ ] 聚类 stage 跑完 ≥ 3 member 才入表（< 3 不入），重跑幂等
- [ ] Dashboard `/insights/patterns` 页面能展示 pattern 列表 + drill-down 看 member + 跳 trace
- [ ] `mvn -pl skillforge-server -am test` 全绿
- [ ] `cd skillforge-dashboard && npm run build` EXIT 0
- [ ] 跑 1 周 dogfood 后人工 spot-check 20 条 LLM outcome label 准确率 > 70%（写到 delivery-index）
- [ ] 不动 SessionEntity / ChatService / SessionService / CompactionService / AgentLoopEngine 任何字段（grep diff 证明）

## 后续 Backlog（不在本包）

- V2 SKILL-CANARY-ROLLOUT 用本包 `outcome` 标签作 canary 指标 baseline
- V3 ATTRIBUTION-AGENT 读本包 `t_session_pattern` 作为输入
- 人工标签修正 UI / source=human 写入（V3）
- 标签噪声反向修正标注 prompt（V3 之后）
- ML 聚类 / embedding 相似度（V5+ 评估）
