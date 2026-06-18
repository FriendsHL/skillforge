# SkillForge 文档

> 更新于：2026-06-17（新增 2 个 blog 复盘 backlog：WF-CONCURRENT-PIPELINE + EVOLVE-JUDGE-GROUNDING；新增 references/autoevolving-capability-stage-2026-06-17.md 自进化现状快照）
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
| **AUTOEVOLVING-MASTER** ⭐ | autoEvolving 父需求总包（Karpathy autoresearch 思想根）。整合 4 surface A/B + canary + Iron Law + DREAMING memory + OPT-REPORT + 14-stage flywheel + autoResearch（V2 接入）+ DSL workflow 编排 + 框架自进化（V5）。**V1 已交付 / V2-V5 待启动** | V1 已交付 / V2-V5 待启动 | [需求包](requirements/active/2026-05-28-AUTOEVOLVING-MASTER/index.md) | — | — | — | — |
| **AUTOEVOLVE-CLOSE-LOOP** | 闭环采纳 + 对靶改进 + benchmark 验证。P1/P2/G5/BC-M1/BC-M2a/engine-fix/阶段A 已交付，阶段B(EVOLVE-BADCASE-SENSITIVITY)/G3/P3 未做 | 部分交付 | [需求包](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md) | — | [PRD](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/prd.md) | [tech-design](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/tech-design.md) | [delivery-index](delivery-index.md) |
| **EVOLVE-JUDGE-GROUNDING** | 自进化判定与候选对靶优化（blog 复盘）。Phase 1 = 配对/comparative 判定（复用已有 perScenarioFlips 做 net-wins 判据，治绝对打分噪声→0 赢家），无 schema 改动；Phase 2+ grounding/refuter/held-out 列 roadmap | Phase 1 已交付 / Phase 2 见下行 | [需求包](requirements/active/2026-06-17-EVOLVE-JUDGE-GROUNDING/index.md) | [MRD](requirements/active/2026-06-17-EVOLVE-JUDGE-GROUNDING/mrd.md) | [PRD](requirements/active/2026-06-17-EVOLVE-JUDGE-GROUNDING/prd.md) | [tech-design](requirements/active/2026-06-17-EVOLVE-JUDGE-GROUNDING/tech-design.md) | — |
| **EVOLVE-CANDIDATE-GROUNDING** | EVOLVE-JUDGE-GROUNDING Phase 2：候选 per-badcase grounding + 最小 delta 编辑（治 live net -7 候选制造回归）。决策经 architect 对抗 review ENDORSE-WITH-CHANGES（A+C-seam）；退出标准 ratify。无 schema 改 | Phase 2 已交付 | [需求包](requirements/active/2026-06-18-EVOLVE-CANDIDATE-GROUNDING/index.md) | [MRD](requirements/active/2026-06-18-EVOLVE-CANDIDATE-GROUNDING/mrd.md) | [PRD](requirements/active/2026-06-18-EVOLVE-CANDIDATE-GROUNDING/prd.md) | [tech-design](requirements/active/2026-06-18-EVOLVE-CANDIDATE-GROUNDING/tech-design.md) | — |

> 整体方案：[plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md](plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) —— 数据飞轮 / 优化闭环 6 版本拆分（**V1-V6 全部已交付**，⑤ A/B 自动 trigger 真闭环 prompt+skill 双 surface 通）

> 最近已交付需求（含 SYSTEM-AGENT-TYPING Phase 1+2 / V6 FLYWHEEL-LOOP-CLOSURE 等）见下方"已交付方案"表 + [delivery-index.md](delivery-index.md)。

## Backlog 和暂缓

| ID | 标题 | 状态 | 文档 |
| --- | --- | --- | --- |
| **OUTCOMES-RUBRIC-FOUNDATION** | 借鉴 Managed Agents Outcomes (`t_rubric` entity + grader 隔离 audit + V2 `AgentLoopEngine` 第 5 轴 exit) — 跟 active DREAMING-MEMORY-EXTENSION 同次研究产出，用户拆开独立 ship | backlog | [需求包](requirements/backlog/OUTCOMES-RUBRIC-FOUNDATION/index.md) |
| **WF-CONCURRENT-PIPELINE** | 学 CC Dynamic Workflows 的并发 pipeline（stage 间重叠、无 barrier）补强 workflow 引擎（现 `pipeline()` V1 串行）。归属 AUTOEVOLVING V2(d) DSL Phase 2。来源 2026-06-17 blog 复盘 | backlog | [需求包](requirements/backlog/WF-CONCURRENT-PIPELINE/index.md) |
| **WEBSEARCH-SEARXNG-BACKEND** | WebSearch SearXNG 自部署 backend。重要不紧急；当每周搜索调用量/费用明显升高，或隐私/内网搜索诉求出现时再启动 | backlog | 见 [todo.md](todo.md) |
| **AUTORESEARCH-OPTIMIZATION** | **重定位为 AUTOEVOLVING V2 (a) 子需求**：autoResearch 自动调研外部（arxiv + GitHub trending）→ LLM 2-stage 抽取 → Iron Law 人审 → backlog。V1 dashboard 留 placeholder「AUTORESEARCH V1 to ship」，本包 ship 后接入数据。原 PRD 5 D + FR + AC + 4 sprint 划分仍有效 | prd-draft (V2 排期) | [需求包](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/index.md) / [MRD](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/mrd.md) / [PRD](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/prd.md) / [tech-design](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/tech-design.md) |
| SEC-1 | Channel 配置加密 | deferred | [需求包](requirements/backlog/SEC-1-channel-config-encryption/index.md) |
| BUG-G | 防御性 follow-up | deferred | [需求包](requirements/deferred/BUG-G-defensive-hardening/index.md) |
| P9-4 | Partial compact（按位置切） | deferred | [需求包](requirements/deferred/P9-4-partial-compact/index.md) |
| EVAL-DYNAMIC-USER-SIM | 动态用户模拟多轮评测（Phase 2/3） | backlog | 见 [todo.md](todo.md) |
| TEAM-COORDINATOR-FOUNDATION | 多 Agent 协作可见性基础 | deferred | [需求包](requirements/deferred/TEAM-COORDINATOR-FOUNDATION/index.md) |

## 已交付方案

已交付需求优先归档到 `requirements/archive/`。历史 `design-*.md` 已合并进对应需求包的 `tech-design.md`；少量无法归属到单个需求的资料保留在 `references/` 或 `operations/`。交付事实以 [delivery-index.md](delivery-index.md) 为准。

| ID | 标题 | 需求包 | 技术方案 |
| --- | --- | --- | --- |
| MCP-HTTP-ANYSEARCH | MCP HTTP transport + 远程 server AnySearch 接入（MCP-CLIENT-MVP 延期到 V2 的 HTTP transport 续作；`McpHttpTransport` 务实版 Streamable HTTP + V152 transport/url/headers schema + core collectTools 工具可见性 bug 修复 + V153/V154 seed&绑定&路由 + dashboard transport UI / Full pipeline 5 reviewer 对抗 2 blocker 修复 / commit `9c57a9fc`）| [需求包](requirements/archive/2026-06-15-MCP-HTTP-ANYSEARCH/index.md) | [tech-design](requirements/archive/2026-06-15-MCP-HTTP-ANYSEARCH/tech-design.md) |
| AUTOEVOLVING-V1-DSL-DASHBOARD | autoEvolving V1：DSL workflow 编排引擎 + `/autoevolving` dashboard 端到端测试 milestone（Sprint 1-4 全交付，2026-05-29，commit Sprint1 `9000bd5` / Sprint3 `b675ee7` / Sprint4 `85ff279` + LLM fail-fast `9049ef8`）| [需求包](requirements/archive/2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/index.md) | [tech-design](requirements/archive/2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/tech-design.md) |
| AUTOEVOLVE-AGENT-FLYWHEEL | autoEvolve 进化 loop：BUG-1 winner-carry-forward + 每轮 A/B 反思回流下一轮候选 + judge per-case rationale（方案 B）（2026-06-01/06-02 交付）| [需求包](requirements/archive/2026-05-31-AUTOEVOLVE-AGENT-FLYWHEEL/index.md) | [tech-design](requirements/archive/2026-05-31-AUTOEVOLVE-AGENT-FLYWHEEL/tech-design.md) |
| AUTOEVOLVE-AGENT-LEVEL-BUNDLE | autoEvolve 路 B：候选=整-agent 自洽状态包（prompt+rules 多面一起改）+ 整体分 A/B + 爬坡（Phase 1-4 全交付，2026-06-02，Full pipeline 双 reviewer PASS）| [需求包](requirements/archive/2026-06-02-AUTOEVOLVE-AGENT-LEVEL-BUNDLE/index.md) | [tech-design](requirements/archive/2026-06-02-AUTOEVOLVE-AGENT-LEVEL-BUNDLE/tech-design.md) |
| DREAMING-MEMORY-EXTENSION V1 | 借鉴 Anthropic Managed Agents Dreaming — `memory-curator` dogfood + transcript dreaming 路径（superseded 原 LlmMemorySynthesizer 设计） / 4 commit `4b6f5af` + `eee33d1` + `558fc46` + `4602c2d` / V120-V123 migration (memory_proposal.evidence_json + memory-curator transcript tools + optimization_event memory_context + attribution-curator memory_context) / 6 task superpowers plan 跑通：transcript-backed reflection proposals + read-only transcript provider + MemoryContextProvider + ListRelevantMemoriesTool + OptimizationEvent memory_context audit + attribution-curator memory_context access / dogfood verify: run-once → proposal 25/26 evidence_json 写入 → approval 后 t_memory 161 created + 0 直接 memory 写 before approval | [需求包](requirements/archive/2026-05-28-DREAMING-MEMORY-EXTENSION/index.md) | [tech-design](requirements/archive/2026-05-28-DREAMING-MEMORY-EXTENSION/tech-design.md) |
| WEB-TOOLS-HARDENING V1 | WebSearch / WebFetch 稳定性 + 信息保留升级 — WebSearch `application.yml` 有序 priority list（Tavily > Exa > DuckDuckGo HTML，决策演进 3 轮后 SearXNG 移 backlog）+ Tavily/Exa 原生下推参数 + 统一 JSON 输出 + WebFetch html→markdown (flexmark-html2md-converter) + Caffeine 15min 缓存 + robots.txt hard block + hostAllowlist + JSON 输出 / Mid pipeline 单 commit `1be8f7d` / `mvn -pl skillforge-tools test` ~56/0/0 + 2 skipped BUILD SUCCESS / Iron Law 0 diff | [需求包](requirements/archive/2026-05-27-WEB-TOOLS-HARDENING/index.md) | [方案](requirements/archive/2026-05-27-WEB-TOOLS-HARDENING/tech-design.md) |
| FLYWHEEL-AB-AGENT-AWARE-DATASET V1 | A/B agent-role-aware dataset filter + benchmark 通用 vs agent-specific 分类（V117 加 `t_eval_scenario.applicable_agent_roles JSONB` + GIN partial idx + 5 ILIKE backfill + AC-1 RAISE EXCEPTION enforce / 5 role taxonomy general/code/design/research/main_assistant / A/B 跑 target subset + regression subset union / Full pipeline 双 phase r1+r2 fix）| [需求包](requirements/archive/2026-05-25-FLYWHEEL-AB-AGENT-AWARE-DATASET/index.md) | [方案](requirements/archive/2026-05-25-FLYWHEEL-AB-AGENT-AWARE-DATASET/tech-design.md) |
| BEHAVIOR-RULE-AB-EVAL V1 | behavior_rule surface A/B 评测框架（V114-V116 migration / `BehaviorRuleAbEvalService` filter+fallback + with-vs-without + 复用 `SystemPromptBuilder.appendBehaviorRules` / dual-criteria target≥+10pp 且 regression≥-3pp / Full pipeline 双 phase r1+r2 8 fix / OptimizationEventAutoTriggerListener.dispatchBehaviorRuleAutoAb 真实现替代 stub）| [需求包](requirements/archive/2026-05-24-BEHAVIOR-RULE-AB-EVAL/index.md) | [方案](requirements/archive/2026-05-24-BEHAVIOR-RULE-AB-EVAL/tech-design.md) |
| EVAL-DATASET-LAYER V1 | Eval 数据层重构（V109-V113 加 source_type/purpose 字段 + EvalDataset 实体 + 30 benchmark scenarios 种子 / GAIA Lv1 12 + τ-bench 6 + AgentBench OS+DB 6 + dogfood 6 全用机器可判分 oracle / Full pipeline 5 round / 解决飞轮 A/B baseline=0% 永不 promote 痛点）| [需求包](requirements/archive/2026-05-24-EVAL-DATASET-LAYER/index.md) | [方案](requirements/archive/2026-05-24-EVAL-DATASET-LAYER/tech-design.md) |
| MULTI-DIM-ATTRIBUTION | 飞轮多维归因（attribution 加 `infrastructure_failure` + `cost_high` 维 / session-annotator 0-msg error session synthetic agent_error signal / WriteOptimizationEventTool 24h auto-cooldown / `MIN_MEMBERS_INFRA_OUTCOME=2` cluster+dispatcher 对齐 / Full pipeline Plan r1+r2 + Java/Code reviewer Opus 1M + Judge）| [需求包](requirements/archive/2026-05-21-MULTI-DIM-ATTRIBUTION/index.md) | [方案](requirements/archive/2026-05-21-MULTI-DIM-ATTRIBUTION/tech-design.md) |
| ATTRIBUTION-DISPATCHER-AGENT | 飞轮调度子系统真接 cron（新 `attribution-dispatcher` system agent + `DispatchAttributionPatterns` Tool / V93 seed agent + UPDATE schedule task #6 agent_id 9→12 / SystemAgentMonitorService 5→6 entry / Full pipeline Plan r1+r2 + BE-Dev + 4 reviewer 对抗 / 修 cron #6 直接 fire curator 无 patternId 致 attribution loop 空跑）| [需求包](requirements/archive/2026-05-21-ATTRIBUTION-DISPATCHER-AGENT/index.md) | [方案](requirements/archive/2026-05-21-ATTRIBUTION-DISPATCHER-AGENT/tech-design.md) |
| FLYWHEEL-PER-RUN | 飞轮 per-run 跟踪视图（在现有 FLYWHEEL-FLOWCHART panel 加 `[Aggregate \| Per-Run]` mode toggle；per-run 模式左侧 Runs sidebar 列最近 N 个 OptimizationEvent + 点选 → DAG 高亮该 run current step + pre-OptEvent 节点灰化 + Drawer 内容 swap to 该 run 信息；BE 1 新 endpoint `/api/flywheel/runs` aggregate；Mid pipeline 双 dev parallel + 2 reviewer 对抗 + r2 4 mandatory fix）| [需求包](requirements/archive/2026-05-20-FLYWHEEL-PER-RUN/index.md) | — (Lite, decisions in index.md) |
| FLYWHEEL-FLOWCHART | 飞轮工作流流程图视图（替换 FLYWHEEL-VISUAL-STATUS card-style；React Flow + dagre LR auto-layout；4 类节点边框色 + 运行中节点绿色慢闪 ring + reduced-motion outline fallback；edge animated when 相邻 in-flight；read-only invariant；React.lazy + Suspense 让 reactflow+dagre 222KB 推迟到 tab activation，main chunk gzip 反而 -118KB；Mid pipeline 1 dev + 2 reviewer 对抗 + 1 round 5-fix bundle）| [需求包](requirements/archive/2026-05-20-FLYWHEEL-FLOWCHART/index.md) | — (Lite, decisions in index.md) |
| FLYWHEEL-VISUAL-STATUS | 飞轮可观测面板（observability framing；Insights 第 5 tab `flywheel`；AUTO/USER/HYBRID/ENTRY 4 类节点 + in-flight/lastActivity/lag/recentError/todayAggregate 5 维 metric + healthy/warn/stale/dormant/empty 5 健康颜色（含 H/W/S/D/E 字母 a11y fallback）+ 双 tab agentType × surface + ARIA tablist 语义 + panel 只读 drill-down 跳现有 page；1B URL routing 改 Insights/SkillList/SessionList/SkillDrafts 4 page 真消费 query param；2B BE 3 endpoint：`/skills/abtest` global + `/canary/rollouts` agentId optional + `/skill-drafts?source=` filter；Full pipeline 1.0 取证 + 2 dev parallel + 3 reviewer 对抗 r1+r2 mandatory bundle 8 fix；mvn 1865/0/109 + tsc + vitest 9/9 + Iron Law 0 触碰）| [需求包](requirements/archive/2026-05-20-FLYWHEEL-VISUAL-STATUS/index.md) | [MRD](requirements/archive/2026-05-20-FLYWHEEL-VISUAL-STATUS/mrd.md) / [PRD](requirements/archive/2026-05-20-FLYWHEEL-VISUAL-STATUS/prd.md) / [tech-design](requirements/archive/2026-05-20-FLYWHEEL-VISUAL-STATUS/tech-design.md) |
| CHAT-MSG-TIMESTAMP | chat 气泡 hover 显 HH:MM:SS server-side createdAt（Full pipeline 1 轮 r1 + 1 mandatory fix; BE DTO+SessionService+WS handler / FE normalize+formatTime+hover CSS / Iron Law 全 PASS / mvn 1853/0/104 + tsc + vitest 三绿 + BE 真活 curl 验 createdAt ISO-8601 出现 / Judge 1 round fix BE 注释诚实 + FE :focus-within A11y） | [需求包](requirements/archive/2026-05-19-CHAT-MSG-TIMESTAMP/index.md) | — (Lite, decisions in index.md) |
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
