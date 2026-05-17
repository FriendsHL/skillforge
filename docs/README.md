# SkillForge 文档

> 更新于：2026-05-17
> Agent 规则：先读这里，再只打开当前任务链接到的文档。

编辑 docs 前，先读 [DOCS-GOVERNANCE.md](DOCS-GOVERNANCE.md)。

## 从这里开始

| 需求 | 阅读 |
| --- | --- |
| 当前执行队列 | [todo.md](todo.md) |
| 已完成交付事实 | [delivery-index.md](delivery-index.md) |
| 已知 bug 和 follow-up | [bugs.md](bugs.md) |
| 文档治理规则 | [DOCS-GOVERNANCE.md](DOCS-GOVERNANCE.md) |
| 重整前长版 ToDo | [references/legacy-todo-2026-04-28.md](references/legacy-todo-2026-04-28.md) |

## 当前需求

| ID | 标题 | 状态 | 需求包 | MRD | PRD | 技术方案 | 交付 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| _(暂无 active 主线需求；SYSTEM-AGENT-TYPING Phase 1+2 整包已交付归档 2026-05-17)_ | — | — | — | — | — | — | — |

> 整体方案：[plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md](plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) —— 数据飞轮 / 优化闭环 6 版本拆分（**V1-V6 全部已交付**，⑤ A/B 自动 trigger 真闭环 prompt+skill 双 surface 通）

### SYSTEM-AGENT-TYPING Phase 1+2 整包 — 已交付 2026-05-17 ✅（飞轮 layer 1 真闭环 + system agent UX 分辨）

Phase 1 (`e659b0a`) DB SQL 反查发现 layer 1 starvation 修飞轮; Phase 2 (pending commit) 给 dashboard 用户 toggle + monitor + 编辑保护 + Chat gate.

#### Phase 2 — system agent UX 分辨 + 监控 inline + 编辑保护

| 项 | 修法 | 状态 |
|---|---|---|
| F2 AgentList toggle | "Show system agents" Switch + localStorage 持久化 default off + 紫色 Tag "System" + agentType=user/all query | ✅ 已交付 |
| F4 SystemAgentMonitorCard inline | inline 进 AgentCard (不开独立 page per user 集中): cron / last_run + status / 7d trigger+output / Run Manually 真触发 / View Sessions + View Schedule deep-link | ✅ 已交付 |
| F3 AgentDrawer 锁 | banner ⚠️ + 全 form input/textarea/picker/Switch readOnly + 4 Save disabled + ASK/AUTO disabled + Delete button disabled + Tooltip (no Unlock per user simplify) | ✅ 已交付 |
| W3 fieldset 兜底 | BehaviorRulesEditor + LifecycleHooksPanel 不接 disabled prop → fieldset wrapper 原生 HTML disable 全 descendant + opacity:0.65 | ✅ 已交付 |
| F5 BE filter + monitor endpoint | AgentController agentType param (default 'user') + AgentService listAgents overload 不破 3 callers + new SystemAgentMonitorController + 跨表聚合 service | ✅ 已交付 |
| F6 Chat send gate | isSystemAgent → input+send disabled + Alert banner + handleSend 头部 defense guard | ✅ 已交付 |
| W2 deep-link 真消费 | SessionList + Schedules useSearchParams 读 ?agentId/?taskId (Judge mandatory fix) | ✅ 已交付 |

**mvn 1797/0/95 + tsc + npm build 双绿 + 21 FE tests PASS (5 file) + 0 regression + Iron Law 核心 7+1 BE + 3 FE 0 diff + 3 reviewer 全 PASS (java Opus + ts Opus + Judge Opus) + 1 mandatory W2 fix done + W3 confirm done**. 详见 [delivery-index.md](delivery-index.md) SYSTEM-AGENT-TYPING Phase 2 行.

**3 reviewer 调度教训记账**: 第一次 + 重派 Sonnet (java/ts/code) 全 600s stalled (3059 行 diff watchdog 触发) → 换 Opus 1M context 跑通. 后续大 diff (>2500 行) 默认上 Opus reviewer 不要 Sonnet.

#### Phase 1 — 飞轮 layer 1 真闭环 (commit `e659b0a`)

| 项 | 修法 | 状态 |
|---|---|---|
| 数据模型 | V89 加 `t_agent.agent_type` VARCHAR(16) NOT NULL DEFAULT 'user' + CHECK + UPDATE 5 system agent | ✅ 已交付 |
| Entity + Bootstrap self-heal | AgentEntity 加字段 (Jackson 自动序列化无需新 DTO) + 5 Bootstrap idempotent `setAgentType('system')` 启动自愈 + 跟 prompt-swap 解耦 | ✅ 已交付 |
| **R1 3-tier annotator fix** | `SessionAnnotationRepository.findRecentBySourceAndAgentType` 原生 SQL JOIN + `SessionAnnotationSignalService` 改 user-first / system backfill / catch-all orphan fallback | ✅ 已交付 |
| **W1 per-tier early dedup** | Phase 2 java-reviewer 抓 edge case: Tier 1 全 LLM-annotated 时 guard 看 pre-dedup 跳 Tier 2 → 空 queue. Fix moves dedup to each tier (red-green 4 步真验) | ✅ 已交付 |
| FE Zod schema | `schemas.ts` AgentSchema 加 `agentType: z.enum(['user', 'system']).optional().nullable()` 防 zod silent strip | ✅ 已交付 |
| 真活验证 | BE 重启 + V89 apply + 触发 session-annotator-hourly 一轮 → **50s 内 user agent outcome 0 → 9** (Main 5 failure / Design 2 / Research 1 / Session Analyzer 1) | ✅ 已交付 |

**mvn 1776/0/95 BUILD SUCCESS + Iron Law 核心 7+1 BE + 3 FE 0 diff + 3 reviewer (java/db/ts Sonnet) PASS 0 blocker + Judge Opus 1 mandatory W1 fix done**. 详见 [delivery-index.md](delivery-index.md) SYSTEM-AGENT-TYPING Phase 1 行.

**Phase 2 UX 留 SYSTEM-AGENT-TYPING 同包后续 PR** (~2-3d Mid): AgentList toggle + AgentDrawer 锁 + Chat gate + SystemAgents 监控面板 + BE filter endpoint.

### V6 FLYWHEEL-LOOP-CLOSURE — 已交付 2026-05-17 ✅

修飞轮 ⑤ A/B 真闭环 4 缺口 + 砍 canary（dogfood 单用户阶段）+ V3.2 link 接通（V88 旁路列）:

| 缺口 | 修法 | 状态 |
|---|---|---|
| A: V3 attribution candidate 没 A/B endpoint | 加 `POST /api/agents/{id}/prompt-versions/{versionId}/run-ab` + `POST /api/skills/{parentSkillId}/abtest-from-draft` | ✅ 已交付 |
| B+C: candidate_ready → A/B 没 EventListener | `OptimizationEventStageChangeEvent` + `OptimizationEventAutoTriggerListener` 三重注解 (@Async + @TransactionalEventListener AFTER_COMMIT + @Transactional REQUIRES_NEW) | ✅ 已交付 |
| D: SkillDraft attribution path stub | `SkillDraftService.createDraftFromAttribution` 加 sync LLM fill（跟 `SkillDraftService.extractFromRecentSessions` 同款 xiaomi-mimo/mimo-v2.5-pro pattern，修正 V3.1 假设错） | ✅ 已交付 |
| F: system agent 没 EvalScenario | `/run-ab` 内 ephemeral scenario fallback 从 `OptimizationEvent.patternId` 反查 → `t_pattern_session_member` 3 session → `extractFromSession` 生 status='ephemeral' + 跑完 `EphemeralScenarioCleanupService` REQUIRES_NEW cleanup | ✅ 已交付 |
| **副作用: canary logic disable** | FE 删 `<CanaryPanel>` embed (SkillAbPanel + BehaviorRuleEvolutionPanel) + V87 migration disable metrics-collector-hourly cron + ALLOWED_TRANSITIONS 加 `ab_passed → promoted` 直接边跳 canary + **保留 t_canary_* schema + V2 CanaryRolloutService dormant** 未来加回 ~2 行 reverse | ✅ 已交付 |
| **附加: V3.2 link 接通 (UUID type mismatch fix)** | V88 加 `candidate_prompt_version_uuid` + `candidate_skill_draft_uuid` VARCHAR(36) 旁路列 (旧 Long 列 type mismatch — UUID surface 留 null + 向后兼容) | ✅ 已交付 |

**8 ratify 决策已锁** (canary disable / SkillDraft fill 跟 SkillDraftService.extractFromRecentSessions / @EventListener / system agent fallback / V88 旁路列 / Phase 1.3-1.4 boundary placeholder / Phase 1.4 升 Full 档 / SkillEntity transient identity name 后缀). **mvn 1750/0/89 BUILD SUCCESS + Iron Law 核心 7+1 BE + 3 FE 0 diff**. 详见 [delivery-index.md](delivery-index.md) FLYWHEEL-LOOP-CLOSURE 行.

## Backlog 和暂缓

| ID | 标题 | 状态 | 文档 |
| --- | --- | --- | --- |
| SEC-1 | Channel 配置加密 | deferred | [需求包](requirements/backlog/SEC-1-channel-config-encryption/index.md) |
| BUG-G | 防御性 follow-up | deferred | [需求包](requirements/deferred/BUG-G-defensive-hardening/index.md) |
| P9-4 | Partial compact（按位置切） | deferred | [需求包](requirements/deferred/P9-4-partial-compact/index.md) |
| EVAL-DYNAMIC-USER-SIM | 动态用户模拟多轮评测（Phase 2/3） | backlog | 见 [todo.md](todo.md) |
| TEAM-COORDINATOR-FOUNDATION | 多 Agent 协作可见性基础 | deferred | [需求包](requirements/deferred/TEAM-COORDINATOR-FOUNDATION/index.md) |

## 已交付方案

已交付需求优先归档到 `requirements/archive/`。历史 `design-*.md` 已合并进对应需求包的 `tech-design.md`；少量无法归属到单个需求的资料保留在 `references/` 或 `operations/`。交付事实以 [delivery-index.md](delivery-index.md) 为准。

| ID | 标题 | 需求包 | 技术方案 |
| --- | --- | --- | --- |
| SYSTEM-AGENT-TYPING | agent_type 字段 + 飞轮 layer 1 root cause 真闭环 (Phase 1) + system agent UX 分辨 + 监控 inline + 编辑保护 + Chat gate + deep-link consume (Phase 2) — Full pipeline × 2 / 真活验证 50s 内 user agent outcome 0→9 / mvn 1797 + 21 FE tests + 0 regression + Iron Law 0 diff | [需求包](requirements/archive/2026-05-17-SYSTEM-AGENT-TYPING/index.md) | [方案](requirements/archive/2026-05-17-SYSTEM-AGENT-TYPING/tech-design.md) |
| FLYWHEEL-LOOP-CLOSURE | 飞轮 ⑤ A/B 自动 trigger 真闭环 prompt+skill 双 surface + canary logic disable + V3.2 link 接通（飞轮 V6）— Mid → upgraded Full / 8 sub-phase + Phase 2 r1+r2 / 5 mandatory fix / 8 ratify + 11 reviewer findings 全处置 + dogfood event 42 真跑通 | [需求包](requirements/archive/2026-05-17-FLYWHEEL-LOOP-CLOSURE/index.md) | [方案](requirements/archive/2026-05-17-FLYWHEEL-LOOP-CLOSURE/tech-design.md) |
| ATTRIBUTION-AGENT | 飞轮归因 + Optimization Event 因果链（飞轮 V3）— BE+FE 闭环 + 5 phase review + 2 BLOCKER tx-propagation fix + sentinel race-window 防御 + bypass-cooldown ratify | [需求包](requirements/archive/2026-05-15-ATTRIBUTION-AGENT/index.md) | [方案](requirements/archive/2026-05-15-ATTRIBUTION-AGENT/tech-design.md) |
| SKILL-AB-MULTITURN-FIX | Skill A/B 多轮评测修复（EVAL-DYNAMIC-USER-SIM Phase 1 拆分包；fix `SkillAbEvalService` multi-turn fallback warning → 真跑 `conversationTurns` + multi-turn judge + candidate skill 注入） | [需求包](requirements/archive/2026-05-13-SKILL-AB-MULTITURN-FIX/index.md) | [方案](requirements/archive/2026-05-13-SKILL-AB-MULTITURN-FIX/tech-design.md) |
| SKILL-CANARY-ROLLOUT | Skill 灰度（架构保留）+ 生产指标回流（飞轮 V2）— BE+FE 闭环 + 3 phase review + tx-isolation fix + silent-failure fix | [需求包](requirements/archive/2026-05-15-SKILL-CANARY-ROLLOUT/index.md) | [方案](requirements/archive/2026-05-15-SKILL-CANARY-ROLLOUT/tech-design.md) |
| PROD-LABEL-CLUSTER | 生产 Session 标注 + 失败聚类（飞轮 V1）— BE+FE 闭环 + Phase 2 review + W2 Blocker fix | [需求包](requirements/archive/2026-05-14-PROD-LABEL-CLUSTER/index.md) | [方案](requirements/archive/2026-05-14-PROD-LABEL-CLUSTER/tech-design.md) |
| MULTIMODAL-MVP | 多模态输入 Phase 1（图片 + PDF 上传 + agent 独立多模态模型 + 上传 gate） | [需求包](requirements/archive/2026-05-14-MULTIMODAL-MVP/index.md) | [方案](requirements/archive/2026-05-14-MULTIMODAL-MVP/tech-design.md) |
| SKILL-SECURITY-SCAN | Skill 安装时快速静态安全扫描 | [需求包](requirements/archive/2026-05-13-SKILL-SECURITY-SCAN/index.md) | [方案](requirements/archive/2026-05-13-SKILL-SECURITY-SCAN/tech-design.md) |
| MEMORY-LLM-SYNTHESIS | LLM 驱动梦境系统（完全 dogfood option A / 4 类 proposal + 人审 gate / V68+V69） | [需求包](requirements/archive/2026-05-11-MEMORY-LLM-SYNTHESIS/index.md) | [方案](requirements/archive/2026-05-11-MEMORY-LLM-SYNTHESIS/tech-design.md) |
| OBS-2 | Trace 数据模型统一（`t_llm_trace` / `t_llm_span` 单轨 + `t_trace_span` drop） | [需求包](requirements/archive/2026-05-02-OBS-2-trace-data-model-unification/index.md) | [方案](requirements/archive/2026-05-02-OBS-2-trace-data-model-unification/tech-design.md) |
| SKILL-DASHBOARD-POLISH-V2 | Skill Dashboard Polish V2（概览卡 + draft merge UX + version tree） | [需求包](requirements/archive/2026-05-08-SKILL-DASHBOARD-POLISH-V2/index.md) | — (Lite, decisions in index.md) |
| SKILL-DASHBOARD-POLISH | Skill Dashboard Polish V1（同名聚合 + evolution detail + A/B 手动操作 + Drafts 入口） | [需求包](requirements/archive/2026-05-08-SKILL-DASHBOARD-POLISH/index.md) | — (Lite, decisions in index.md) |
| REMINDER-MVP | system-reminder 框架 Phase A（ReminderBuilder + 3 source + P9-5 recovery payload 迁移到 `<system-reminder>` 包装） | [需求包](requirements/archive/2026-05-09-REMINDER-MVP/index.md) | [方案](requirements/archive/2026-05-09-REMINDER-MVP/tech-design.md) |
| P9-5 | Post-compact 恢复（FileStateCache + recovery payload + 4 路径自动注入） | [需求包](requirements/archive/2026-05-09-P9-5-post-compact-recovery/index.md) | [方案](requirements/archive/2026-05-09-P9-5-post-compact-recovery/tech-design.md) |
| MEMORY-DREAM-CONSOLIDATION | Dream Consolidation cron（夜间 memory 整理 + embedding dedup + archived_reason + 手动触发） | [需求包](requirements/archive/2026-05-08-MEMORY-DREAM-CONSOLIDATION/index.md) | — (Lite, decisions in index.md) |
| PROMPT-CACHE-MVP | Prompt Cache MVP（5 provider 全链路 + Claude 3 breakpoint + dashboard hit rate） | [需求包](requirements/archive/2026-05-08-PROMPT-CACHE-MVP/index.md) | [方案](requirements/archive/2026-05-08-PROMPT-CACHE-MVP/tech-design.md) |
| MCP-CLIENT-MVP | MCP Client MVP（stdio + per-agent + dashboard CRUD + dogfood time） | [需求包](requirements/archive/2026-05-07-MCP-CLIENT-MVP/index.md) | [方案](requirements/archive/2026-05-07-MCP-CLIENT-MVP/tech-design.md) |
| P10 | 聊天斜杠命令 MVP（8 命令 + dashboard popup + channel BE 拦截） | [需求包](requirements/archive/2026-05-07-P10-slash-commands/index.md) | [方案](requirements/archive/2026-05-07-P10-slash-commands/tech-design.md) |
| P12 | 定时任务 MVP（user-type cron + one-shot + 5 Tool + dashboard） | [需求包](requirements/archive/2026-05-07-P12-scheduled-tasks/index.md) | [方案](requirements/archive/2026-05-07-P12-scheduled-tasks/tech-design.md) |
| EVAL-V2 | 评测系统改造（M0-M6 全闭环） | [需求包](requirements/archive/2026-05-07-EVAL-V2-overhaul/index.md) | [方案](requirements/archive/2026-05-07-EVAL-V2-overhaul/tech-design.md) |
| P12-PRE | Sprint 4 前置决策（Cost Dashboard / PG 备份 / 多用户权限） | [需求包](requirements/archive/2026-05-04-P12-PRE-preflight-decisions/index.md) | — (Lite, decisions in index.md) |
| P6 | 消息行存储 | [需求包](requirements/archive/2026-04-21-P6-session-message-storage/index.md) | [方案](requirements/archive/2026-04-21-P6-session-message-storage/tech-design.md) |
| SEC-2 | Hook Source Protection | [需求包](requirements/archive/2026-04-25-SEC-2-hook-source-protection/index.md) | [方案](requirements/archive/2026-04-25-SEC-2-hook-source-protection/tech-design.md) |
| Memory v2 | 写入 / 召回 / 淘汰 | [需求包](requirements/archive/2026-04-27-MEMORY-v2/index.md) | [方案](requirements/archive/2026-04-27-MEMORY-v2/tech-design.md) |
| OBS-1 | Session x Trace 合并详情视图 | [需求包](requirements/archive/2026-04-29-OBS-1-session-trace/index.md) | [方案](requirements/archive/2026-04-29-OBS-1-session-trace/tech-design.md) |
| OBS-4 | 跨 agent / 跨 session trace 串联（root_trace_id） | [需求包](requirements/archive/2026-05-03-OBS-4-root-trace-id/index.md) | [方案](requirements/archive/2026-05-03-OBS-4-root-trace-id/tech-design.md) |
| CTX-1 | 三档触发接全量估算 + 阈值配置化 + 撞窗 retry | [需求包](requirements/archive/2026-04-30-CTX-1-context-token-accounting/index.md) | [方案](requirements/archive/2026-04-30-CTX-1-context-token-accounting/tech-design.md) |
| P1-D | Skill Root 与 Catalog 收口 | [需求包](requirements/archive/2026-04-30-P1-D-skill-root-catalog-convergence/index.md) | [方案](requirements/archive/2026-04-30-P1-D-skill-root-catalog-convergence/tech-design.md) |
| SKILL-LOAD | Skill Loader Tool | [需求包](requirements/archive/2026-04-30-SKILL-LOAD-skill-loader-tool/index.md) | [方案](requirements/archive/2026-04-30-SKILL-LOAD-skill-loader-tool/tech-design.md) |
| P9-2 | Tool Result 归档 + BUG-32 长任务修复 | [需求包](requirements/archive/2026-04-30-P9-2-tool-result-archive/index.md) | [方案](requirements/archive/2026-04-30-P9-2-tool-result-archive/tech-design.md) |
| SKILL-IMPORT | 第三方 marketplace 装包导入 SkillForge | [需求包](requirements/archive/2026-05-01-SKILL-IMPORT-third-party-marketplace/index.md) | [方案](requirements/archive/2026-05-01-SKILL-IMPORT-third-party-marketplace/tech-design.md) |
| SKILL-IMPORT-BATCH | 扫描 marketplace whitelist batch import | [需求包](requirements/archive/2026-05-01-SKILL-IMPORT-BATCH-rescan-marketplace/index.md) | [方案](requirements/archive/2026-05-01-SKILL-IMPORT-BATCH-rescan-marketplace/tech-design.md) |
| MSG-1 | 消息类型化 + ask_user 持久化 + 非阻塞 waiting_user | [需求包](requirements/archive/2026-04-30-MSG-1-message-typing/index.md) | [方案](requirements/archive/2026-04-30-MSG-1-message-typing/tech-design.md) |
| P5 | 前端体验优化 | [需求包](requirements/archive/2026-04-19-P5-frontend-optimization/index.md) | [方案](requirements/archive/2026-04-19-P5-frontend-optimization/tech-design.md) |
| P2 | Channel Gateway | [需求包](requirements/archive/2026-04-20-P2-channel-gateway/index.md) | [方案](requirements/archive/2026-04-20-P2-channel-gateway/tech-design.md) |
| P4 | Code Agent | [需求包](requirements/archive/2026-04-19-P4-code-agent/index.md) | [方案](requirements/archive/2026-04-19-P4-code-agent/tech-design.md) |
| P7 | 飞书 WebSocket | [需求包](requirements/archive/2026-04-21-P7-feishu-websocket/index.md) | [方案](requirements/archive/2026-04-21-P7-feishu-websocket/tech-design.md) |
| P9 | Tool 输出裁剪 / 压缩 | [需求包](requirements/archive/2026-04-22-P9-tool-result-compaction/index.md) | [方案](requirements/archive/2026-04-22-P9-tool-result-compaction/tech-design.md) |
| N1 | Memory 向量检索 | [需求包](requirements/archive/2026-04-16-N1-memory-vector-search/index.md) | [方案](requirements/archive/2026-04-16-N1-memory-vector-search/tech-design.md) |
| N2 | Agent 行为规范层 | [需求包](requirements/archive/2026-04-17-N2-behavioral-rules/index.md) | [方案](requirements/archive/2026-04-17-N2-behavioral-rules/tech-design.md) |
| N3 | Lifecycle Hooks | [需求包](requirements/archive/2026-04-17-N3-lifecycle-hooks/index.md) | [方案](requirements/archive/2026-04-17-N3-lifecycle-hooks/tech-design.md) |
| N3-P1 | Lifecycle Hook 增强 | [需求包](requirements/archive/2026-04-17-N3-P1-lifecycle-hook-enhancement/index.md) | [方案](requirements/archive/2026-04-17-N3-P1-lifecycle-hook-enhancement/tech-design.md) |
| P1 | Self-Improve Pipeline | [需求包](requirements/archive/2026-04-20-P1-self-improve-pipeline/index.md) | [方案](requirements/archive/2026-04-20-P1-self-improve-pipeline/tech-design.md) |
| INSTALL-CONFIRM | Install Confirmation 授权流 | [需求包](requirements/archive/2026-04-26-INSTALL-confirmation-flow/index.md) | [方案](requirements/archive/2026-04-26-INSTALL-confirmation-flow/tech-design.md) |
| SESSION-STATE | 会话状态 / ask mode | [需求包](requirements/archive/2026-04-17-SESSION-state-ask-mode/index.md) | [方案](requirements/archive/2026-04-17-SESSION-state-ask-mode/tech-design.md) |
| SUBAGENT | SubAgent 异步派发 | [需求包](requirements/archive/2026-04-16-SUBAGENT-async-dispatch/index.md) | [方案](requirements/archive/2026-04-16-SUBAGENT-async-dispatch/tech-design.md) |
| BROWSER-LOGIN | BrowserSkill 登录态 | [需求包](requirements/archive/2026-04-17-BROWSER-skill-login/index.md) | [方案](requirements/archive/2026-04-17-BROWSER-skill-login/tech-design.md) |

## 参考与运维

| 需求 | 阅读 |
| --- | --- |
| LLM provider 踩坑 | [LLM provider 踩坑录](references/llm-provider-quirks.md) |
| Dashboard 视觉参考 | [前端视觉参考](references/design-references.md) |
| Eval 方法论 | [Eval 方法论](references/design-eval-methodology.md) |
| P6 灰度手册 | [operations/p6-rollout-playbook.md](operations/p6-rollout-playbook.md) |

## 归档规则

- `requirements/active/`：当前或近期需求。
- `requirements/backlog/`：未来可能做，但未排期。
- `requirements/deferred/`：V2 或明确暂缓。
- `requirements/archive/`：已完成需求。
- `references/`：长期参考资料。
- `operations/`：运维手册和脚本。
