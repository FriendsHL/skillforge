# Wiki Takeaway Triage — 把 123 个 R-候选 cross 进《能力现状审计》 — 2026-06-20

> 类型：dated triage（一次性过滤产物，会随交付推进过期）。
> **两个输入**：① 权威 ground truth = [skillforge-capability-audit-2026-06-20.md](./skillforge-capability-audit-2026-06-20.md)（8 维能力图，path:line 经源码查证）；② 123 个 R-候选 = wiki `research/agent-harness-wiki/` 各页 `skillforge_takeaway` frontmatter + comparison/deep 页正文的 `R-*` 借鉴清单。
> **本文作用**：逐个把候选 cross 进能力图，过滤出"真缺 / 半截"的，产出《真需求清单》。**不是新需求来源 —— 交付事实唯一来源仍是 [delivery-index.md](../delivery-index.md)。**

---

## 1. 一句话

123 个 R-候选 cross 进 8 维能力图后：**丢弃 88（已被能力图证明已有）/ 真需求 9（落 4 缺口）/ 补全 19（落半截维度）/ 待人工裁定 7**。

**最重要的诚实结论**：能力图的 4 个"真缺口"里，**最易摘的那个（Skill 低使用 curator）在 123 个 wiki 候选里居然没有任何一条直接命中** —— R-SKILLS 全家（CrewAI skills）讲的是 SKILL.md 格式 / progressive disclosure / 安装安全，**没人提"低使用自动归档"**。这条缺口是**审计自己从"Memory 有 curator、Skill 没有"的不对称里推出来的**（audit §4），不是 wiki 给的。其余 3 缺口都有候选命中（per-goal budget = R-GOAL-6 / R-MA-1；outcome-driven = R-AMA-OUT-1/2；taint = R-GOOSE-2 半侧）。

**收敛情况符合预期**：真需求高度集中在 **Self-Evolution（per-goal budget + autoEvolving 判定 + outcome-reached）+ Safety（taint）** 两块；多 agent（R-MA / R-AT 全家)、memory 归档清理（R-MEM-CON 大半)、tool-approval、命令黑名单/SSRF/XML 沙箱、A/B eval pipeline **整批被"已有"覆盖**，如实丢弃。

---

## 2. 《真需求清单》主表（核心产物 — 只列"真需求"+"补全"）

按 **4 缺口聚类**排序；同缺口下多候选已合并去重。"现状"列引能力图 file:line。

### 缺口 A — Self-Evolution 类3 per-goal token budget 完全没有（真缺口）

| 来源 R-编号 | 候选建议（一句） | 对应缺口 | SkillForge 现状（引能力图 file:line） | 建议动作 | 优先级 |
|---|---|---|---|---|---|
| **R-GOAL-1/2/6** + **R-MA-1**(crewai R-CW-1 / metagpt R-MG-1 合并) | Thread Goal 一等 entity + per-goal token_budget + 软降级 wrap-up（超预算不硬 raise，注入 budget_limit.md 让 LLM 收尾）；多 agent 协作时 `cost_manager.max_budget` 防账单失控 | 缺口④之 per-goal budget | 「全代码无按 goal 维度的 token_budget（entity/config/service 都没有）；仅有整体 agent-loop 级预算」`audit:84`；维度1 token 预算是 loop 级且 opt-in `AgentLoopEngine.java:611-638`(`audit:36`) | **新立项 `GOAL-BUDGET`**：`thread_goals` 表（objective/status/token_budget/tokens_used）+ ThreadGoalService + turn 结束 budget check 软降级。R-GOAL-3 `continuation.md` completion-audit prompt 直接抄（防 LLM 偷换/提前 mark complete） | **P1** |

### 缺口 B — Self-Evolution 类1 DREAMING 无自动批准 + 类4 autoEvolving 空转 0 真赢家（真缺口，判定尺子问题）

| 来源 R-编号 | 候选建议（一句） | 对应缺口 | SkillForge 现状（引能力图 file:line） | 建议动作 | 优先级 |
|---|---|---|---|---|---|
| **R-AMA-MEM-5** | `LlmMemorySynthesizer` 输入扩到 `sessions[]`（从整 session 转录挖未观测到的 pattern，而非只 reorganize 已有 memory）| 类4 候选不对靶（喂给判定的候选质量太弱是空转主因之一）| 维度5 类4「框架全在但空转 0 真赢家…候选不对靶 + 绝对打分 self-preferential」`audit:85`；DREAMING approve→执行**已接通** `MemoryProposalService.java:112-118`(`audit:83`)，缺的是自动批准+对靶候选 | **并入 EVOLVE-JUDGE-GROUNDING**（already active）作为"候选生成质量"上游补强；新增 `SessionTranscriptProvider` + token budget。**注意**：不是补执行管线（已接通），是补"挖得出新候选" | **P1** |
| **R-INSIGHT-1** | MULTIPLE-TIMES 跨 session 重复 pattern 识别 → 自动建议 behavior_rule（给 autoEvolving 喂"真实高频痛点"候选）| 类4 候选不对靶 | 同上 `audit:85`；已有 V80 attribution state machine，缺上游 source | **并入 EVOLVE-JUDGE-GROUNDING** 的候选来源层（"有了真实高频信号，候选才对靶"）| **P2** |
| **R-GOAL-3** | `continuation.md` completion-audit prompt（反 LLM 偷换 objective / 提前判完成）| 类1 无自动批准的相邻防御（自主闭环若开，必须有反偷懒 gate）| 维度5「类1 缺自动批准闭环（目前全人审）」`audit:89` | 若未来给 DREAMING / autoEvolving 开自主闭环，**先抄这套 completion-audit prompt 当 gate**，再谈 auto-approve | **P2（前置）** |

### 缺口 C — Safety 无 taint/quarantine 自动降权（真缺口）

| 来源 R-编号 | 候选建议（一句） | 对应缺口 | SkillForge 现状（引能力图 file:line） | 建议动作 | 优先级 |
|---|---|---|---|---|---|
| **R-GOOSE-2**（部分）| adversary_inspector + egress_inspector 双向 LLM 风防御 6 文件 | 缺口② taint/quarantine | 「无 `TaintTracker`/`QuarantineRegistry`/`UntrustMarker` 类；`ToolApprovalRegistry` 不吃'agent 是否刚读过不可信外部内容'信号」`audit:110` | **新立项 `SAFETY-TAINT`**：读外部内容（web/MCP/file）打 taint marker → `ToolApprovalRegistry` 消费该信号自动升档 ask/deny。goose 的 inspector 是 LLM-judge **补充层**，core 是运行时污点位 | **P1** |
| **R-AMA-AUDIT-1** | 验证 SkillForge judge LLM 是否真独立 context window（防 grader 被 main agent reasoning trace 污染）| 缺口②相邻（信息隔离）+ 维度6 判定尺子 | 维度6「判定是绝对加权分（self-preferential 风险）」`audit:100`；grader 隔离性能力图未单独查证 | **15 分钟 audit（无代码改）**：grep judge/grader prompt 拼装点，确认输入只含 rubric+artifact。结果若污染 → 并入 EVOLVE-JUDGE-GROUNDING | **P2** |

### 缺口 D — 类3 outcome-reached / 类1 outcome-driven loop（真缺口 ④ 之 close-the-loop）

| 来源 R-编号 | 候选建议（一句） | 对应缺口 | SkillForge 现状（引能力图 file:line） | 建议动作 | 优先级 |
|---|---|---|---|---|---|
| **R-AMA-OUT-1** + **R-AMA-OUT-2** | `AgentLoopEngine` 加第 5 轴 exit `outcome_satisfied`：用户显式声明"done 长什么样"(rubric)，engine 评分不满意就再来一轮；`t_rubric` 独立可版本化 entity | 缺口④ engine 无 close-the-loop ground（现只靠 LLM 自觉说停）| 维度1「4-axis loop exit」`AgentLoopEngine.java:587-590`(`audit:32`,skillforge.md:1040)，**无第 5 轴 outcome**；维度6 rubric inline 在 prompt，缺独立 entity `audit:100` | **新立项 `OUTCOME-DRIVEN-LOOP`**（大）：`t_outcome`+`t_rubric`+`OutcomeService`+5-state result + grader 走独立 `LlmJudgeService` client（与 R-AMA-AUDIT-1 同源，防 token 偷溜）。R-AMA-OUT-2(t_rubric)可先独立发布（基础设施复利）| **P2**（OUT-2 可拆 P1 小步）|

### 半截维度 — 补全需求（已有基础，需加强）

| 来源 R-编号 | 候选建议（一句） | 对应半截维度 | SkillForge 现状（引能力图 file:line） | 建议动作 | 优先级 |
|---|---|---|---|---|---|
| **R-CC-COMPACT-11** + **R-RX-FP-1/2/3** + **R-AS-3 / R-CC-COMPACT-6** | ① Fold/summary call 复用同一 prefix 走 cache；② PromptPrefix class + sha256 fingerprint + mutator 集中失效 + dev tripwire；③ pydantic 强类型 summary schema 防 hallucination | Context（✅ 但有 trade-off）| 维度2「引擎层无字节一致 roundtrip 运行时 guard，靠规则/review/测试模板」`audit:55`；不变量已是有意识防御纵深 `audit:148` | **补全 `COMPACT-CACHE-GUARD`**：fingerprint+roundtrip 断言把"靠纪律"升级到"引擎层 guard"。**注意**：能力图说这是有意取舍，非疏漏 → P2，别推翻 | **P2** |
| **R-CC-COMPACT-2** + **R-CC-COMPACT-12** | 反复压缩 <10% 保护 + ToolCallRepair 把 retry 阻于源头（anti-thrashing） | Loop（🟡 detectWaste 偏弱）| 维度1「detectWaste 启发式/阈值偏弱」`AgentLoopEngine.java:698,760`(`audit:37`)；已有 compact circuit breaker `BREAKER_TRIP_THRESHOLD=3`(skillforge.md:1043) | **补全 `LOOP-WASTE-HARDENING`**：把 <10% 压缩保护 + retry 源头修复并入 detectWaste 启发式 | **P2** |
| **R-GOAL-6 软降级（loop 侧）** + **R-CC-4**(input budget default-on) | input-token 预算从 opt-in 改 default-on 或文档化 + 软降级 | Loop（🟡 input token 预算 opt-in 软）| 维度1「maxInputTokens default 500K 但 OPT-IN…默认软约束，仅 telemetry 无 hard stop」`AgentLoopEngine.java:611-638`(`audit:36`) | **补全**：review P9-2 取舍，长任务场景 default-on（`audit:149` 已标为值得 review 的取舍）| **P3** |
| **R-INSIGHT-3** + **tau-bench FaultType** + **AgentOps AGENT_* 3 sub-kind** | typed enum 归因 schema（goal/outcome/friction… + USER/AGENT/ENV + thinking/action/decision）给 ATTRIBUTION-AGENT 输入更结构化 | Self-Evolution 类4 判定（半截）| 维度5/6 判定尺子 `audit:85,100`；已有 V80 14-stage attribution state machine | **补全**：扩 `t_session_annotation` / span sub-kind，喂 EVOLVE-JUDGE-GROUNDING 更细输入 | **P2** |
| **R-MEM-CON-3** + **zep-graphiti validity windows** + **R-COG-1 trace-feedback 入 memory** | Memory 加 temporal validity windows（valid_from/to → 自动 supersede 旧事实）+ trace/feedback 自动 routed 进 memory pipeline | Memory（✅ 但 temporal 维度缺）| 维度3「四级生命周期…无实质缺口」`audit:68` —— **归档清理已满**，但 temporal-validity/feedback-routing 是能力图未覆盖的*新维度*，非已有缺口 | **补全（谨慎）**：能力图说 Memory 无缺口 → 这两条是"加新能力"非"补缺口"，**标待裁定倾向补全**，先跑 LongMemEval 看是否真需要 | **P3** |

---

## 3. 丢弃清单（grounded 过滤，按"已有"5 类分组）

能力图证明 SkillForge 已有 → 丢弃。压缩呈现：每类列命中的 R-编号 + 一句"为何已覆盖"。

### 已有① Memory 归档清理（MemoryConsolidator TTL/容量/去重）— 证据 `MemoryConsolidator.java:157-203` / `MemoryEntity.java:52-65`(`audit:133`)

- **R-MEM-CON-1/2/4/7、R-CC-COMPACT-3、R-AMA-MEM-2、R-PW-1** — 压缩前 memory extraction hook / 3-gate 后台 consolidation 调度 / Promotion Score / 梦境日记 / dedup batch：全部落在已落地的 `MemoryConsolidator` 四段（dedup/TTL-archive/capacity-demote/expired-delete）+ 5-turn stale 提醒。**丢弃**（R-MEM-CON-3 例外，见补全表 temporal）。
- **R-AMA-MEM-1/3、R-MASTRA-2、R-COG-2/3/4/5、R-LI-1/2/4、R-GR-1/2/3/4、R-RF-3/4/5、R-LG-1、R-DIFY-5、langmem/mem0/mempalace/graphiti memory 细节** — synthesisInstructions / 多层 memory scope / 22 retriever / KG / property graph / hierarchical index / entity linking / cross-encoder rerank：**memory 检索/架构"调味"类，全部是 Memory ✅ 维度内的增量优化，不落 4 缺口** → 丢弃（mem0/mempalace 的 benchmark 建议保留为"跑 LongMemEval"动作，非新需求）。

### 已有② tool-approval allow/ask/deny — 证据 `ToolApprovalRegistry.java:13-53` / `Decision.java:14-35` / `PendingConfirmationRegistry.java:15-100`(`audit:134`)

- **R-HM 的 `approvals.mode=smart`、R-RX-FP 无关、claude-code 5 Permission modes、R-RX-FP BUILTIN_ALLOWLIST、opencode permission ruleset** — aux-LLM 预判审批 / 5 档 permission mode / allowlist 互补：三档审批 + HTTP/飞书回调唤醒已有；`auto/plan` mode 细分属"补全 Safety"边角但能力图判 Safety 主体 ✅ → **丢弃**（smart-approval 可作 P3 nice-to-have，不立项）。

### 已有③ 命令黑名单 / 路径穿越 / SSRF / XML 沙箱防注入 — 证据 `SafetySkillHook` / `DangerousCommandChecker` / `McpServerService` SSRF / `SystemPromptBuilder` XML(`audit:135`)

- **R-HM-1(`_DESTRUCTIVE_PATTERNS`)、R-GOOSE-2 的命令侧、R-SKILLS-4(path traversal 双防)、R-DIFY-4(moderation)、R-CC-COMPACT-5(防元提示)、aider git-safety、deepagents THREAT_MODEL、dspy `_sanitize_lm_state`、langfuse boundary、reasonix StormBreaker** — 命令正则 / 路径保护 / SSRF / XML 沙箱 / skill 安装扫描全已落地（`DangerousCommandChecker` 17 正则 + 7 dir + SkillSecurityScanner 9 规则）→ **丢弃**。R-GOOSE-2 仅"读外部内容自动降权"那一侧升级到缺口 C，其余命令侧已有。

### 已有④ SubAgent 树 + Team 网状 + 持久化顺序邮箱（agent 间通信）— 证据 `SubAgentTool` / `TeamCreateTool` / `TeamSendTool` + 持久化邮箱(`audit:137`)

- **R-MA-2/3/5/6/8、R-AT-1/2/3/5/6、R-AG-1/2/3/4/5、R-CW-1（团队侧）、R-MG-2/3、R-MASTRA-3/4、R-DIFY-2、R-CODEX multi-agent actor、R-AMA-MA-1、deep/claude-code-agent-teams ①②③④** — mailbox 通信 / 结果回报标准化 / sessionKey trace / 防 grandchild / speaker selection / Process enum / SubAgent-as-tool / star-控制权限收口：**SkillForge 已有 SubAgent 树(MAX_DEPTH=3/MAX_ACTIVE=5)+ Team 网状(深≤2/总≤20)+ 持久化邮箱 + 顺序投递 + crash recovery**，R-AT 全家 + R-MA 大半是已有变体 → **丢弃**（R-MA-1 cost-budget 那一侧升级到缺口 A；R-MA-4 actor-resume 半截见待裁定；R-MA-7/R-AT-4 Fork 见待裁定）。
- **R-MG-1 cost-aware 终止** 的多 agent 侧并入缺口 A（per-goal/per-team budget），不重复列。

### 已有⑤ A/B eval pipeline + oracle + 自动晋升 — 证据 `AbEvalPipeline` / `BehaviorRulePromotionService`(`audit:138`)

- **R-INSPECT-1/2/3、R-DSPY 全、R-OPIK 1-12、R-LANGFUSE 1-10、R-PROMPTFOO、R-RAGAS、R-PHOENIX、R-AGENTOPS、R-HELICONE、R-SWE-BENCH、R-COG-7、tau-bench schema、R-AG-5 OTel** — Task/Solver/Scorer 抽象 / Score 数据模型 / 7 optimizer / assertion 库 / RAG metric / OTel ingestion / 防 regression：**A/B pipeline + oracle ≥40 + infra 失败摘出分母 + 双准则自动晋升已全到位**；这些是 eval 周边 observability/metric 增量 → **丢弃**。eval 真正待治项是"判定尺子"（已在缺口 B + 补全 R-INSIGHT-3），非 pipeline 本身。

### 已有⑥ 其它已覆盖 / 不适用 / 明确"不该跟"（候选自己标的）

- **R-AMA-MEM-5 物理隔离 thread-pool（R-AMA-AUDIT-2）** — audit 提议级，verify 即可，非需求。
- **R-PI-*（extension API/27 event/OAuth 复用）、R-GOOSE-1/3/4/5/6（MCP-as-extension/hook 数量/ACP/跨家 provider）、R-CODEX hooks 数量、R-SMOL-3 step-type hooks、R-MASTRA-1 processor、R-LG-2/3/4、R-LI-3/5、R-DIFY-1/3/6、R-CW-2/3/4、R-RF-1/2/6、R-COG-6、R-PW-2/3、R-PROTO、R-RAG-3、R-HARNESS-1(LSP)、R-AS-1/2/4、R-OH-2、R-SMOL-1/2/4/5、opencode LSP** — hook 范式/数量、provider/extension 抽象、LSP、sandbox executor、code-mode、checkpoint SPI、RAG parser、A2A：**全部不落 4 缺口**，是 harness 设计空间的"架构启示/远期参考",或 SkillForge 已明确"不做"(Code Mode/ACP/single-provider/Fork-优先)。LSP(R-HARNESS-1/opencode)是 coding-agent 真缺口但**不在本审计 8 维范围内**（能力图未列 Skill/LSP 工具层为缺口维度）→ 归"丢弃（超出本次 cross 范围）"，单独存 backlog 不在此表升级。

---

## 4. 待人工裁定（7 条）

| R-编号 | 为何难判 | 倾向 |
|---|---|---|
| **R-MA-4 / R-CODEX actor-resume** | actor mailbox + `resume_agent` 中断恢复 —— 能力图维度4 判 Multi-Agent ✅ 无缺口，但"长任务 interrupt/resume"是 fire-and-forget 之外的*新能力*，不是已有缺口也不是 4 缺口之一 | 倾向"补全/远期"，非真需求；裁定是否进 multi-agent roadmap |
| **R-MA-7 / R-AT-4 / R-OH fork_context** | Fork-style subagent KV-cache reuse —— SkillForge **故意选完全隔离**(`skillforge.md:1141` 🚫 不做 Fork)，但 cache 复用有性能价值 | 裁定：维持"不做"还是开 feature gate POC |
| **R-AMA-MEM-5（sessions[] 那部分能力扩展）** | 既算缺口 B 候选（喂对靶候选），又是 Memory ✅ 维度的新能力扩展 —— 边界模糊 | 已暂列缺口 B P1；裁定归 EVOLVE 还是 MEMORY roadmap |
| **R-MEM-CON-3 + graphiti validity windows + R-COG-1** | 能力图说 Memory **无实质缺口**(`audit:68`)，但 temporal-validity / trace-feedback-routing 是图里没出现的维度 —— 不能算缺口也不宜直接丢 | 已暂列补全 P3；裁定是否真需要（先跑 LongMemEval） |
| **R-AMA-OUT-3/4/5（outcome chain/序列）** | 仅作为 R-AMA-OUT-1 的延伸出现，本体是缺口 D，但 chain/sequence 是 P3 协议扩展 | 随 OUTCOME-DRIVEN-LOOP 立项后再裁优先级 |
| **smart-approval（R-HM `approvals.mode=smart`）** | 能力图判 tool-approval ✅，但"aux-LLM 预判减少打扰"是 UX 增量，不是缺口 | 倾向丢弃/P3 nice-to-have；裁定是否值得做 |
| **R-HARNESS-1 / opencode LSP** | coding-agent 真缺口，但**超出本审计 8 维范围**（Skill 维度审的是 curator 不是 LSP）| 裁定是否另开 coding-agent 能力审计，不在本 cross 表 |

---

## 5. 建议落地

### 可直接在 `docs/requirements/backlog/` 立项（4 个新需求）

1. **`GOAL-BUDGET`（P1）** — 缺口 A。Thread Goal entity + per-goal token_budget + 软降级。来源 R-GOAL-1/2/6 + R-MA-1。直接抄 R-GOAL-3 completion-audit prompt。这是 4 缺口里 wiki 候选最完整、最 actionable 的一条。
2. **`SAFETY-TAINT`（P1）** — 缺口 C。读外部内容打 taint marker → `ToolApprovalRegistry` 消费信号自动升档。来源 R-GOOSE-2（运行时污点位为 core，LLM inspector 为补充层）。
3. **`SKILL-CURATOR`（P1，wiki 无候选 —— 审计自荐）** — 缺口③。**最易摘的低垂果实但 123 候选无人提**：复用 `MemoryConsolidator` 的 TTL/容量模式套到 `SkillEntity`（usageCount 已有数据没人消费，`audit:121,147`）。**立项依据是审计 §4 不对称观察，不是 wiki** —— 如实标注来源。
4. **`OUTCOME-DRIVEN-LOOP`（P2，OUT-2 可拆 P1 小步）** — 缺口 D。`AgentLoopEngine` 第 5 轴 outcome_satisfied + `t_rubric` 独立 entity + 独立 grader client。来源 R-AMA-OUT-1/2。t_rubric 可先独立发布（基础设施复利）。

### 并入已在跑的需求

- **并入 `EVOLVE-JUDGE-GROUNDING`（active）**：R-AMA-MEM-5（sessions[] 挖对靶候选）+ R-INSIGHT-1（高频 pattern 当候选源）+ R-INSIGHT-3/tau-bench/AgentOps（typed 归因 schema）+ R-AMA-AUDIT-1（grader 隔离 15 分钟 audit）。这些都是"让判定尺子更准 + 候选更对靶"，正是 EVOLVE-JUDGE-GROUNDING 的靶心，**不另立项**。
- **并入 Context/Loop 加固（无独立 epic 则挂 backlog 杂项）**：R-CC-COMPACT-11/2/12 + R-RX-FP-1/2/3 + R-CC-4 input-budget default-on。能力图明示这些是"有意识防御纵深"非疏漏（`audit:148-149`），**P2/P3 增量不抢 4 缺口的 P1 排期**。

### 一句话给排期

**先做 3 个 P1（GOAL-BUDGET / SAFETY-TAINT / SKILL-CURATOR），它们一一对应能力图 4 缺口里 ROI 最高的 3 个**；SKILL-CURATOR 工程量最小（复用现成 MemoryConsolidator 模式）应最先动手。OUTCOME-DRIVEN-LOOP 和 EVOLVE-JUDGE-GROUNDING 补强是 P2 的产品能力演进，体量大、排在 P1 之后。

---

## 6. 用户裁定 + 现状澄清（2026-06-20 逐条 review）

> 用户逐条过了 §2/§5 的建议，结论固化于此 —— **此节优先级高于 §5 的原始排期**（§5 是 triage 初判，本节是 review 后的实际决定）。

| 条目 | 裁定 | 理由 |
|---|---|---|
| **SKILL-CURATOR**（缺口③）| ✅ **批准，已入 backlog**（commit `46fa45e5`，`docs/requirements/backlog/SKILL-CURATOR/`）。**当前唯一"适合现在做"的** | 真缺口（无 SkillConsolidator，已查证）+ 复用 MemoryConsolidator 模式 + 不碰核心文件 + 不跟任何决定冲突 |
| **SAFETY-TAINT**（缺口C）| 🟡 真 P1、价值高（防提示注入），但**排在 SKILL-CURATOR 之后**（新子系统、体量较大）| 设计确认：**复用现有 ask-confirmation 路**（`ToolApprovalRegistry` ask → `PendingConfirmation` 卡片）；策略 = **有人能确认→ask / 无人值守→deny**（不能 ask 干等，否则卡死，同 AC-3 reachability 坑）|
| **GOAL-BUDGET**（缺口A）| ❌ **暂不做** | 本质是 token 预算护栏，跟用户对 agent-teams"成本/时间护栏暂不需要"的决定**同类冲突** → 同步 deprioritize |
| **OUTCOME-DRIVEN-LOOP**（缺口D）| ❌ **通用 loop 不做**，窄化为"eval 可选增强" | 用户 push back + Claude 同意：**不同任务停止标准千差万别**，给通用 loop 加强制 rubric 第5轴 = 过度设计（大多数任务用不上、加摩擦）；真需 outcome 验收的 **eval/AUTOEVOLVE 已有 rubric+judge**。`OUTCOMES-RUBRIC-FOUNDATION` 留 backlog **不升级** |
| **压缩/循环加固**（补全）| 🟡 大部分跳过 | 健壮性打磨、triage 自己标"有意取舍非疏漏"。**唯"反复压缩 <10% 就停"** 并入 `COMPACT-IDEMPOTENCY` ② tool-heavy 待办（同战场），其余（指纹 guard/cache/强类型 schema/input 预算 default-on）忽略 |

### 现状澄清（危险命令实际行为 —— 别再记错）

经源码查证（`SafetySkillHook.checkBashSafety` + `DangerousCommandChecker`）：

- **`rm -rf /` / `sudo` / `mkfs` / `dd if=` / `shutdown` / `curl|sh` 等 `DANGEROUS_PATTERNS`** → **硬 block（`return null`，直接拒执行）**，**auto 和 ask 两模式都拦死，不走人审批**。
- **唯一"要人确认"档（`CONFIRMATION_REQUIRED_PATTERNS`）= skill 安装命令**（`clawhub/skillhub install`），走 install-confirmation gate（未经 gate 到 hook = fail-closed 拒）。
- → 即 SkillForge 现有安全哲学：**真危险 = 直接拒（不问人）/ 中危（装东西）= 问人**。**SAFETY-TAINT 沿用此哲学**（污点+高危→deny / 污点+中危→ask）。**勿记成"rm 走人审批"**。
