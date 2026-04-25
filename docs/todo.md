# SkillForge 待办任务

> 更新于：2026-04-23
> **对抗整理**：Analyst + Challenger 双 Agent 评审 + Claude 仲裁（2026-04-23）
> **代码扫描校准**：全量代码现状核查后二次修正（2026-04-23）
> **🔥 紧急条目新增**：ENG-1 / ENG-2 / P9-5-lite，由 session `9347f84c` 真实事故触发（2026-04-23）
> P 编号保留历史，**不代表当前优先级**；以 Sprint 顺序为准。

---

## 待排期

### 📋 执行顺序总览

| Sprint | 内容 | 预估 | 核心判断 |
| --- | --- | --- | --- |
| ~~**🔥 紧急**~~ | ~~ENG-1~~ · ~~ENG-2~~ · ~~P9-5-lite~~ | 1-2 天 | 由 session 9347f84c 真实事故触发；阻断 Design Agent 长任务；**全部完成 2026-04-23** |
| **Sprint 1** | P9-7 · P3-1 · P3-3 · P13-3 ~~· P13-4~~ | 2-3 天 | 零依赖防腐；P13-4 代码扫描确认已完成；实际比估算省力 |
| **Sprint 2a** | P11（收窄）+ P13-1 | 5-7 天 | AgentDiscovery + SubAgent 按 name + visibility；去掉 capabilities/tags |
| **Sprint 2b** | P15（最小闭环） | 3-5 天 | GetTrace + GetSessionMessages + Analyzer seed；跑真实 case 验证后再扩 |
| **Sprint 3** | P9-2 长对话 tool 归档（独立 PR） | ~2 周 | 触碰核心文件，Full Pipeline；真实用户长 session 慢性病 |
| **⚠️ 前置决策** | Cost Dashboard · PG 备份 · 多用户权限 design doc | 决策先行 | Sprint 4 开工前必须有答案，否则 P12 上线即踩坑 |
| **Sprint 4** | P12 定时任务（收窄首版） | 3-4 周 | user 型调度最小集；SystemJobRegistry + P12-6 → V2 |
| **Sprint 5** | P9-4 · P9-5（需 design doc 先行） | 按需 | P9-5 依赖 P9-4；P9-5 需先明确"最近文件"数据来源 |
| **Sprint 6** | P10 斜杠命令（收窄 4 条） | 5-8 天 | 从 Sprint 1 降级；/compact 只做 full；/model 只改 session 级 |
| ~~**🔥 穿插**~~ | ~~Compact Breaker 误触 + LLM Stream 抗抖（BUG-A/B/C/D/E/E-bis）~~ | ~~Full Pipeline~~ | ✅ 2026-04-24 完成（commit `121e8dc`）；Full Pipeline 运行 + 额外捕获 `FullCompactStrategy.callLlm` 吞异常的更深 root cause |
| **🔒 穿插** | SEC-1 Channel 配置 AES-GCM 加密 | 1-2 天 | 代码扫描发现明文存储安全问题，P12 前修复 |
| **🔒 穿插** | SEC-2 系统 Hook 存储分离与保护 | Full Pipeline | 当前无系统 hook 概念且无校验，curl / JSON 模式可删任意 hook；方案 A 存储分离 vs B source 标记 |
| **🧹 穿插** | DEBT-1 SkillList.tsx 拆分（47K 单文件） | 3-5 天 | 低优先级，下次动 SkillList 前先拆 |
| **V2** | P14 · P3-2/4 · P15-3/4/6 · P11-3 · P12-3/6 · P10-4/5 · P13-9 | 推迟 | 见底部 V2 推迟池 |

> **工期修正说明**：Analyst 对 P12 给出"1-2 周"，Challenger 实测拆解后为 3-4 周（时区/夏令时/concurrencyPolicy/前端 cron 编辑器是主要坑）。P10 "1-2 天"实测 5-8 天（`/compact` 触碰 CompactionService 核心文件 + Full Pipeline）。

---

### 🔥 紧急 — Agent Loop 引擎修复（Sprint 1 前置，1-2 天）

> **触发事件**：session `9347f84c`（Design Agent 改造 dashboard）在 `4 light · 2 full · -77.0K tok` 之后，准备重写 802 行的 `FormMode.tsx` 时，连续 3 次 tool_use 的 input 退化为 `{}`，全部返回 `"file_path is required"`，最后 LLM 返回空字符串静默退出。
>
> **链路**：full compaction 丢失 pending FileWrite 内容 → LLM 生成空参数 → 3 次 validation 错误被 `detectWaste` 当 consecutive error → 又触发 B1 compaction → context 二次坍塌 → idle 退出。
>
> **状态**：Sprint 1 前置，全部走 Full Pipeline（触碰 `AgentLoopEngine.java` / `CompactionService.java` 两个核心文件）。

| 子任务 | 说明 | 关联 |
| --- | --- | --- |
| ~~**ENG-1**~~ `SkillResult.errorType` + `detectWaste` 区分 validation/execution ✅ 2026-04-23 | `SkillResult.ErrorType` enum (VALIDATION/EXECUTION) + `validationError(msg)` 工厂；`ContentBlock.errorType` 字段 + `Message.toolResult` 重载；`AgentLoopEngine` 工具调用结果传播 errorType + `IllegalArgumentException` 单独 catch 标 VALIDATION；`detectWaste` 重写：VALIDATION 在所有规则中视为中性（不增计数 / 不重置已积累 execution 计数 / rule 3 identical-tool-use 也跳过 VALIDATION 调用）；FileEditSkill / FileWriteSkill 的 required 字段缺失改用 `validationError()`。新增 `AgentLoopEngineWasteDetectTest`（5 用例含 9347f84c 回归）。293 测试 0 failure | 治本 bugs#7（当时治标抬阈值 B1 0.40→0.60） |
| ~~**ENG-2**~~ `AgentLoopEngine.executeToolCall` 前置 required-param 校验 + retry hint ✅ 2026-04-23 | `findMissingRequiredFields(tool, input)` 从 `ToolSchema.inputSchema.required` 提取必填字段，校验 input 中 key 存在 + 非 null（空字符串/blank 留给 skill 自身判断）。`executeToolCall` 在 SkillHook.beforeSkillExecute 之后、`tool.execute` 之前插入校验，缺字段时直接返回结构化 hint：`"[RETRY NEEDED] FileWrite missing required argument(s): file_path, content. Re-emit the tool call with all required fields populated."`，标 VALIDATION（与 ENG-1 协同：不触发 detectWaste compaction）。新增 `AgentLoopEnginePreValidationTest`（6 用例：全填 / 9347f84c 空 input / null 值 / 空字符串放行 / schema 无 required / 防御 null）。299 测试 0 failure | 新条目 |
| ~~**P9-5-lite**~~ Compaction prompt 保留 pending FileWrite/FileEdit input ✅ 2026-04-23 | `FullCompactStrategy.SUMMARY_SYSTEM_PROMPT` 在 "MUST preserve" 列表新增一条约束：任何 emit 但未拿到 tool_result 的 pending tool_use，必须 verbatim 保留 tool name + 完整 input arguments，不允许 summarize 任何 FileWrite / FileEdit / Bash 的字符串内容。43 个 compact + engine 测试全绿。**P9-5 完整版（5 文件摘要 + 活跃 skill 上下文 + 完整 pending tasks）保持 V2 排期不变** | 切割自 P9-5（Sprint 5 / V2） |

> **测试要补**：除单元测试外，需用真实 session 复现：构造一个 LLM 准备写 800 行文件的场景，强行触发 full compaction，验证 LLM 恢复后能拿到完整 file content 而不是空 input。

---

### Sprint 1 — 零依赖防腐（4-6 天）

> 全部无强依赖、独立 PR、即刻可开工。P8 LLM 记忆提取刚上线，快照是必要防腐；token 估算精度影响所有用户体验；P13 workaround 清理降低后续改动摩擦。

| 子任务 | 来源 | 实际工作量（代码扫描后） |
| --- | --- | --- |
| **P9-7** jtokkit token 估算增强 | P9 | jtokkit 已在 pom.xml 引入，TokenEstimator 已有估算框架；主要工作是在关键决策点（压缩触发判断）切换到 jtokkit 精确计数 + 增量缓存。比预期省力，约 0.5-1 天 |
| **P3-1** 记忆快照 | P3 | 每次记忆提取前打快照（extraction_batch_id）；支持按 batch 回滚；P8 上线后的质量基础设施，防止脏数据不可回滚。约 1 天 |
| **P3-3** 记忆影响归因扩展 | P3 | AttributionEngine 7×5 矩阵已完整实现；只需新增两个枚举（`MEMORY_INTERFERENCE` / `MEMORY_MISSING`）+ 两个信号（memorySkillCalled / memoryResultEmpty）。约 0.5 天，比预期省力 |
| **P13-3** AgentEntity#isPublic → Boolean 包装 | P13 | 当前是 primitive `boolean`，需改为 `Boolean` 包装类 + schema migration（PG/H2 兼容）+ 所有调用点加 `Boolean.TRUE.equals()` 判断。约 0.5-1 天 |
| ~~**P13-4**~~ AgentServiceTest 补测试 | P13 | **代码扫描发现已完成**：AgentServiceTest 现有 5 个测试（404/full-payload/all-null/isPublic/blankRole），覆盖 todo 要求的全部场景。**划掉** |

---

### Sprint 2a — P11 Agent 发现与跨 Agent 调用（收窄，5-7 天）

> 收窄范围：去掉 P11-3 capabilities/tags（当前 agent 数 < 10，name 模糊查找够用，tag 系统是过度设计）。P13-1 custom rule severity 并入本 Sprint，共享 agent 后端改动节奏。

| 子任务 | 说明 |
| --- | --- |
| **P11-1** AgentDiscoverySkill | 新增 Skill，Agent 可查询平台所有可用 Agent 列表（返回 id/name/description/skills）；支持按名称模糊过滤 |
| **P11-2** SubAgentSkill 增强 | 支持按 agent name（不仅 agentId）调用；调用前自动校验目标 Agent 存在且可用 |
| **P11-4** 调用权限控制 | visibility: public/private 两值（不做 internal 第三类、不做 tags）；循环调用检测；配置哪些 Agent 可被发现和调用 |
| ~~**P11-3**~~ capabilities/tags | **→ V2**（见 V2 推迟池） |
| **P13-1** Custom rule severity（并入） | `BehaviorRuleConfig.customRules: string[]` → `Array<{severity: 'MUST' \| 'SHOULD' \| 'MAY', text: string}>`。前端：`useBehaviorRules` + `BehaviorRulesEditor` custom section UI（加 severity 下拉）。后端：`AgentDefinition.BehaviorRulesConfig.customRules` 类型变更 + `SystemPromptBuilder.appendBehaviorRules` 按 severity 分组注入 + Jackson 向后兼容 deserializer（老数据 `"text"` 自动升级为 `{severity:'SHOULD', text}`） |

---

### Sprint 2b — P15 Agent 自省技能层（最小闭环，3-5 天）

> 目标：把平台查询类 REST API 包成 Skill，让任意 Agent 能查自身的 Trace / Session 数据；内置一个 Session Analyzer Agent 辅助分析工具选择质量。首版只做最小可验证闭环，跑 1-2 个真实 session 人工评价输出质量后再决定是否扩展 P15-3/4/6。

| 子任务 | 说明 |
| --- | --- |
| **P15-1** GetTraceTool | 新增 Skill：`action=list_traces`（按 sessionId 列 trace 摘要）或 `action=get_trace`（按 traceId 拉完整 span 树：AGENT_LOOP → LLM_CALL → TOOL_CALL）；输出裁剪到 `maxSpans=30`，input/output 各截断 500 chars 防 context 爆；复用现有 `TraceSpanRepository` |
| **P15-2** GetSessionMessagesTool | 新增 Skill：按 sessionId 拉消息历史（role/content/toolCalls），支持 `limit` 控制返回条数；复用现有 `SessionMessageRepository` |
| **P15-5** Session Analyzer Agent seed | Flyway migration 种子数据：预配置 Agent（name="Session Analyzer"，绑定 GetTraceTool + GetSessionMessagesTool，system prompt 分析工具选择质量：是否用了过重的工具完成轻量任务 / 是否遗漏更高效工具 / 循环次数是否合理，输出结构化建议 `{issue, actual_tool, better_tool, reason, confidence}`）；手动触发，文本输出，**无前端 tab** |
| ~~**P15-3**~~ GetEvalRunTool | **→ V2**：Analyzer MVP 不需要读 eval run；当前 eval 数据量小，分析价值有限 |
| ~~**P15-4**~~ GetAgentConfigTool | **→ V2**：与 P11-1 AgentDiscoverySkill 功能重叠，P11 排期时合并实现 |
| ~~**P15-6**~~ 分析结果落库 + 前端 Analysis tab | **→ V2**：首版文本输出到 session 即可；落库 + UI tab 等验证有价值后再投 |

#### 与其他 P 的关联
- **→ P11**：P15-4 GetAgentConfigTool 与 P11-1 重叠，P11 排期时合并
- **→ P14**：Analyzer 发现的"应该用 A 但用了 B"case，可一键转为 P14 eval scenario
- **→ P3**：记忆质量分析可复用 GetSessionMessagesTool

---

### Sprint 3 — P9-2 长对话 Tool 归档（独立 PR，~2 周）

> 直接解决"长对话 context 爆满"真实用户慢性病。P6 消息行存储上线后，归档表前置条件已满足，工程复杂度大幅降低。触碰核心文件（CompactionService），走 Full Pipeline 对抗循环。

| 子任务 | 说明 |
| --- | --- |
| **P9-2** Per-message 聚合预算 + 归档持久化 | 单条 user message 的 tool_result 总量超 200K chars 时，按大小降序归档到 `t_tool_result_archive` 表，消息替换为 2KB preview + 引用 ID；可选新增 `ToolResultRetrieveSkill` 让模型按需读取 |

---

### ⚠️ Sprint 4 前置决策项（开工 P12 之前必须有答案）

> Challenger 补充：被 Analyst 完全忽略的三项前置工程设计。P12 定时任务会放大 token 消耗和数据量，没有这三项的决策，P12 上线即踩坑。这三项本身实现量不大，但需要明确设计决策。

| 前置项 | 核心问题 | 建议行动 |
| --- | --- | --- |
| **Cost Dashboard** | P12/P15/未来 P14 都是 token 放大器；无 cost 可见性会出现"跑几天账单爆炸"的情况 | 最小版：session/agent 维度 token 用量 + LLM 估算费用，复用现有 TraceSpan 数据；单页 UI |
| **Embedded PG 备份策略** | zonky embedded PG 无内置备份；data dir 损坏 = sessions/skills/memories/agent 配置/scheduled tasks 全部蒸发；P12 跑起来后更不能没有 | 最小版：pg_dump cron 脚本 + 本地压缩保留 7 天 |
| **多用户/权限模型 design doc** | 当前 auto-token 是单用户假设；P11 跨 Agent 调用身份、P12 定时任务触发 session 的"身份"都踩在此假设上，任何真实多端部署都需要先定 | 写 design doc：多用户如何隔离 agent/session/memory/scheduled_task；不要求立刻实现，但要定好边界 |

---

### Sprint 4 — P12 定时任务（收窄首版，3-4 周）

> 首版只做 user 型调度最小集；SystemJobRegistry + 高级可靠性 → V2。工期修正为 **3-4 周**（Analyst 的"1-2 周"低估了 concurrencyPolicy 三种策略 / 时区夏令时坑 / 前端 cron 编辑器 / 历史表 + AOP 切面的工程量）。

#### 关键设计选择

**调度器选型：** user 型走 `ThreadPoolTaskScheduler` 动态调度（`schedule(Runnable, Trigger)` + DB 持久化元数据，应用启动时从 DB 全量重新注册）。除非明确要做 cluster，否则不引 Quartz。

**首版 system 型任务：** 保留现有 5 处 `@Scheduled` 注解不动；SystemJobRegistry + UI 展示 → V2。

**首版 concurrencyPolicy：** 只支持 `skip-if-running`；queue/parallel → V2。

| 子任务 | 说明 |
| --- | --- |
| **P12-1** Schedule 实体 + CRUD | `t_scheduled_task`（id/name/cronExpr/oneShotAt/timezone/agentId/promptTemplate/channelTarget/enabled/concurrencyPolicy/nextFireAt/lastFireAt/status）+ Flyway migration + `ScheduledTaskService` CRUD + REST API；只存 user 型任务 |
| **P12-2** 动态调度器内核 | `UserTaskScheduler`：基于 `ThreadPoolTaskScheduler` + `CronTrigger`；应用启动时从 DB 全量 register；CRUD 操作后同步 schedule/unschedule；触发时调用 `ChatService.chatAsync` 起 session；首版只支持 `concurrencyPolicy=skip-if-running`；shutdown 优雅等待 |
| **P12-4** 执行历史 + 可观测 | `t_scheduled_task_run`（taskId/triggeredAt/finishedAt/status=success\|failure\|skipped\|timeout/errorMessage/triggeredSessionId）；每次调度写一行 |
| **P12-5** 前端 /schedules 页面 | 只做 user 型 tab（system tab → V2）；任务列表 + cron 表达式编辑器（含"下 5 次触发时间"预览）+ 新建/编辑 drawer（选 Agent + prompt 模板 + channel 目标）+ 启停 toggle + 手动 trigger 按钮 + 执行历史时间线 |
| ~~**P12-3**~~ SystemJobRegistry | **→ V2**：首版保留现有 `@Scheduled` 注解不动；UI 上只做 user 型 tab |
| ~~**P12-6**~~ 可靠性 + 权限 | **→ V2**：首版只做超时 kill + 失败日志；告警推送 + admin 权限分离后续实现 |

---

### Sprint 5 — P9-4 Partial compact + P9-5 Post-compact 恢复（按需）

> P9-5 需要先写 design doc 明确"最近操作文件"数据来源（TraceSpan 查？新表？）再开工，否则工期不可估。P9-4 是 P9-5 的硬前置。

| 子任务 | 说明 |
| --- | --- |
| **P9-4** Partial compact 支持 | `FullCompactStrategy` 新增 `compactUpTo`（压缩头保留尾）和 `compactFrom`（压缩尾保留头）；`ContextCompactTool` 扩展 `level=partial_head/partial_tail` |
| **P9-5** Post-compact 上下文恢复 | Full compact 后自动注入最近操作的文件摘要（5 个文件 / 50K token 预算）+ 活跃 skill 上下文 + pending tasks；**需先完成 design doc 明确"最近操作文件"数据来源后再开工**。**注**：pending FileWrite/FileEdit input 保留这一最小子项已被 P9-5-lite 切到「🔥 紧急」前置完成，本任务排期时不要重复 |

---

### Sprint 6 — P10 聊天斜杠命令（收窄，5-8 天）

> 从 Sprint 1 降级为中后期节奏调节器。Challenger 质疑：斜杠命令替代的是已存在的 UI 路径，不是新能力，实际用户价值被 Analyst 高估（原评 5/5，修正为 2/5）。工期修正为 5-8 天（`/compact` 触碰 CompactionService 核心文件需走 Full Pipeline）。

| 子任务 | 说明 |
| --- | --- |
| **P10-1** 命令注册表（4 条） | `/new`（新建 session）、`/compact`（触发 full compact，不等 P9-4 就绪）、`/clear`（清空当前对话显示）、`/model`（改 session 级临时模型，不持久化到 agent 配置） |
| **P10-2** 前端命令解析 | 聊天输入框拦截 `/` 前缀消息；输入时弹出命令补全菜单（fuzzy match）；回车直接执行，不发送给 Agent |
| **P10-3** 后端命令执行 | REST API `POST /api/chat/commands/{command}` 或复用现有 endpoint；`/new` 调用 SessionService 创建、`/compact` 调用 CompactionService 等 |
| ~~**P10-4**~~ 自定义命令注册 | **→ V2**：4 条命令覆盖 90% 用例 |
| ~~**P10-5 `/help`**~~ | **→ V2**：4 条命令的系统不需要 help menu |

---

### P3 — 记忆质量评估（Sprint 1 + V2）

> P3-1 + P3-3 已拆入 Sprint 1（零依赖，立即可做）。P3-2 + P3-4 依赖 P14 基础设施，随 P14 整体推迟至 V2。

| 子任务 | 状态 | 说明 |
| --- | --- | --- |
| **P3-1** 记忆快照 | **→ Sprint 1** | 见 Sprint 1 |
| **P3-3** 记忆影响归因 | **→ Sprint 1** | 见 Sprint 1 |
| ~~**P3-2**~~ Memory Eval 模式 | **→ V2** | eval sandbox 加入 Memory Skill（只读，读快照不读生产）；新增记忆专属场景集；依赖 P14 基础设施 |
| ~~**P3-4**~~ 记忆质量闭环 | **→ V2** | 每次提取后自动触发 Memory Eval；Δ 为负则标记问题 batch；依赖 P3-2 |

---

### P14 — 多轮对话基准测试（τ-bench 风格 Eval 扩展）

> **整体推迟至 V2。** Challenger 判断：在无真实 τ-bench 级评测需求前，这是学术味的基础设施投资；UserSimulator 收敛难题未解；pass^k 每轮 ×k token 账单；8 个 retail domain tool × 带内存状态 × state assertion oracle，10-20 条场景的移植是隐性大工程。**重评触发条件：真实业务场景需要多轮评测时。**

| 子任务 | 说明（备查，V2 实现） |
| --- | --- |
| P14-1 EvalScenario schema 扩展 | 新增 `conversational`、`userProfile`、`domainState`、`domainTools`、`maxTurns`、`trials`；oracle 新增 `state_assertion` 类型 |
| P14-2 UserSimulatorSkill | 持有 userProfile + goal + conversationHistory；每轮调用 LLM 生成用户下一句话；收敛信号：输出含 `[DONE]` 或 turns 超限 |
| P14-3 多轮场景驱动循环 | `ScenarioRunnerSkill` 新增 `runConversationalScenario`：交替触发 UserSimulator → AgentLoop |
| P14-4 DomainToolRegistry + state_assertion oracle | 按 `domainTools` 动态注入带状态领域 Skill；`EvalJudgeSkill` 新增 state_assertion 判题 |
| P14-5 τ-bench retail 工具 + 场景集 | 8 个 retail domain Skill + 10-20 条 held-out 场景 JSON |
| P14-6 pass^k 指标 | 同一 scenario 重复跑 k 次，统计通过率；前端 Eval 页展示 pass^k 列 |
| P14-7 后续基准接入 | τ-bench airline / MINT / ToolBench 等（更远 V2） |

---

### 🧹 LLM Provider 观测性 / DX（穿插，~1 天，独立 PR）

> 由 2026-04-24 reasoning_content 修复过程中的两点用户反馈触发，非 blocker：

- **缺 api-key 应 fail-fast + 友好错误**：当前 `OpenAiProvider` / `ClaudeProvider` 构造器拿到空字符串 apiKey 仍继续跑，加 `Authorization: Bearer ` 头打出去，上游 HTTP 401 回来才暴露（"auth header format should be Bearer sk-..."），排查陡峭。改：构造器 / `chat` / `chatStream` 入口校验 apiKey 非空非空白，空就抛 `IllegalStateException("Provider '<name>' missing api-key — set ${ENV_VAR} and restart.")`，异常带 provider 名 + 需要的 env var 名。
- **"OpenAI API error" 误导**：`OpenAiProvider` 一个类服务于 OpenAI / DeepSeek / DashScope / vLLM / Ollama 等所有 OpenAI 兼容后端，但错误字符串硬编码为 `"OpenAI API error: HTTP ..."`（`OpenAiProvider.java:123, 202`），用 DeepSeek 被上游拒时日志/UI/stack 仍显示 "OpenAI"。改：构造器多收一个 `providerDisplayName`（"deepseek" / "dashscope" / "bailian" / "openai" / "vllm" / "ollama"），`getName()` 返回它，错误字符串改用它；`LlmProviderFactory:55` 构造时透传 `config.getProviderName()`。架构上更彻底的做法是抽 `DeepSeekProvider extends OpenAiProvider` 或独立 `ThinkingMessageAdapter` 把 `reasoning_content` 逻辑从 `OpenAiProvider` 剥出去，让非 thinking provider 不背这段代码。此项可选。

---

### ~~🔥 引擎稳定性 — Compact Breaker 误触 + LLM Stream 抗抖~~ ✅ 已完成（2026-04-24，commit `121e8dc`）

> 详见底部「已完成」表格 2026-04-24 行。
>
> **Follow-up（非 block，独立 PR，见 `/tmp/nits-followup-engine-stability-v1.md`）**：
> ① E-bis MockWebServer retry 单测补齐（`connectException_succeedsOnSecondAttempt` / `postHandshakeException_doesNotRetry` 等 7 个场景）；
> ② BUG-D `onWarning → TraceSpan.attributes` 端到端测试；
> ③ Dashboard Traces 页显示 TraceSpan `attributes` 面板让 truncation warning 可视。

<details>
<summary>历史子任务描述（原 plan 触发背景，仅作复盘参考）</summary>

> **触发事件**：2026-04-23 下午连续 3 个 session 中断——
> - `cec1cd60`：breaker 错误 open（no-op/idempotency 3 次被当 failure）→ session 全程无法 compact；同时 `Read timed out`（loop 12，qwen thinking idle > 60s）+ `ConnectException /127.0.0.1:1082`（VPN 抖动 ~30s）
> - `02112e8f`：`Read timed out`（60s 默认）
> - `3c0d05d9`：`ConnectException /127.0.0.1:1082`（VPN 抖动，30s 内自愈，但 chatStream 不 retry 就 fail 整个 loop）
>
> **环境约束（不可回避）**：本机 DashScope 域名被 VPN 劫持到 `198.18.0.45 / utun4`，不走 VPN 物理上连不到 qwen；所以 "绕过 VPN" 不是选项，必须代码层抗抖。
>
> **已止血（无需 Pipeline）**：`application.yml:74` bailian `read-timeout-seconds: 60 → 240` 已启用（2026-04-23，commit 暂未 push）。

| 子任务 | 说明 |
| --- | --- |
| **BUG-A** Breaker 误触修复（blocker） | `AgentLoopEngine.java` 3 个 compact 触发点（:352-353 soft / :374-376 hard / :463-465 preemptive）都把 `cr.performed == false` 当 failure 累加。实际 `CompactionService` 的 `noOp` 包含 "session not found" / "idempotency guard" / "strategy returned no-op" / "full compact no-op or in-flight" 多种正常 skip 情形。修：只有**真正抛 Exception** 才 `incrementCompactFailures`，`performed==false` 视为中性。cec1cd60 实证：连续 3 次 no-op/idempotent skip 后 breaker 错误 open，session 整段跑在无压缩状态 |
| **BUG-B** Breaker 自动重置（warning） | `AgentLoopEngine.java:383-386` 一旦 `>=3` 后永远 skip，没有 time-based half-open。修：最后一次失败后 N 秒（建议 60s）允许试一次；或下一次 user turn 入口重置计数 |
| **BUG-C** Compact 失败日志补 stacktrace（warning） | `AgentLoopEngine.java:356-357 / :379-380 / :468-469` 都是 `log.warn(..., e.getMessage())`，**没 stacktrace**，WARN 级。改 `log.error(..., e)` 带完整堆栈；+ `CompactionService` 内部失败路径（如 LLM 超时、解析失败）自行加 ERROR 日志，不依赖调用方 |
| **BUG-D** OpenAiProvider SSE 截断 observability（info） | `OpenAiProvider.java:525-528` 已有 try/catch + `input=Map.of()` 兜底（所以不崩），但前端/trace 完全不知道 "tool_call input 是残缺的"。加：解析失败时通过 handler 发一个可识别 error 类型（或结构化 warning 事件），让 trace / 前端能看到截断信号 |
| **BUG-E** Stream 与 non-stream 共用 HttpClient（blocker） | `OpenAiProvider.java:44-58` / `ClaudeProvider.java:46-58` 构造时一个 `OkHttpClient` 被 non-stream `chat()` 和 stream `chatStream()` 共享，readTimeout 对两者意义完全不同（non-stream=总耗时；stream=相邻 chunk idle）。修：构造两个 client，stream 用更长/无限 idle timeout（参考 `FeishuWsConnector:208` 的 `readTimeout(Duration.ZERO)` 用法）；修正 `application.yml:74` 注释 "non-stream chat only"（事实上 stream 也用）；修正 `application.yml:75-76` `max-retries` 注释（stream 不 apply 是现状，但 ConnectException 类可以 retry，见 BUG-E-bis）|
| **BUG-E-bis** ChatStream 对 ConnectException 短重试（blocker，从 3c0d05d9 直接触发） | 项目 footgun #3 "chatStream 不重试" 是针对**已推 delta** 的 SocketTimeoutException，但对 `ConnectException` / `SSLHandshakeException` / 握手前失败**不适用**——连接没建立，delta 根本没推，retry 安全。修：`OpenAiProvider.chatStream` / `ClaudeProvider.chatStream` 区分异常位置，握手前 retry 1-2 次（间隔 2-5s），握手后不 retry。VPN 抖动（1082 typical 30s 内自愈）期间就不会 fail 整个 agent loop |

</details>

---

### 🔒 安全修复 — Channel 配置明文存储（穿插，独立 PR）

> **代码扫描发现**：`ChannelConfigEntity` / `ChannelConfigService` 有明文 TODO 注释："AES-GCM 加密存储，目前为明文 JSON"。Channel 配置中存有飞书/Telegram 的 Bot Token、Secret 等敏感字段，当前全部明文写入数据库。这是一个安全问题，应在 P12 之前修复（P12 上线后会有更多定时任务 channel 配置写入）。

| 子任务 | 说明 |
| --- | --- |
| **SEC-1** Channel 配置 AES-GCM 加密 | `ChannelConfigService` 存储前加密敏感字段（appSecret / botToken / encryptKey）；读取时解密；密钥从环境变量注入；对已存在明文数据做一次性迁移脚本；约 1-2 天 |

---

### 🔒 系统 Hook 保护（穿插，触碰核心文件，需 Full Pipeline）

> **发现时间**：2026-04-23，讨论 "session 结束自动 memory 压缩" 类系统 hook 时发现。**当前 SkillForge 根本没有 "系统 hook" 概念**：`HookEntry` 无 `source` / `isSystem` 字段，`AgentService.updateAgent` 对 lifecycleHooks 只校验大小/语义（`AgentService.java:63-95`），收到 PUT 就无条件 `setLifecycleHooks()` 覆盖。前端 `JsonMode.tsx` 是个 TextArea，用户可以把 hooks 全删了保存。所以未来如果把系统 hook 当默认值塞进 `agent.lifecycleHooks`，**用户 curl 或 UI JSON 模式都能绕过删掉**，UI 屏蔽只是装饰。
>
> 需求同时包含：UI 要把系统 hook **显示但锁定**（灰卡 + `系统` 徽章，不提供编辑/删除；可折叠分组避免拥挤），让用户知道后台在做什么，保住透明度。

| 子任务 | 说明 |
| --- | --- |
| **SEC-2** 系统 Hook 存储与保护架构 | **优先方案 A（存储分离）**：系统 hook 不存进 `agent.lifecycleHooks`，新增 `SystemHookRegistry`（代码内注册 or 独立表），`HookDispatcher` / `AgentLoopEngine` 执行时合并 user + system；用户配置面只有 user hook，删不掉是因为压根不在那。**备选方案 B**：`HookEntry` 加 `source: "system"\|"user"`，`AgentService.updateAgent` 做 diff 校验 system 条目不变。触碰核心文件 (`HookEntry` / `AgentService` / `AgentLoopEngine` hook dispatcher) + 前后端都要动 → **Full Pipeline**。Plan 阶段让 Planner 出 A/B 对比 |
| **SEC-2-fe** LifecycleHooksEditor 展示系统 hook | 折叠分组 "系统 Hooks (N)" + 灰色只读卡片 + `系统` 徽章；JSON 模式如果保留，需要保证系统 hook 不在用户可编辑的 JSON 范围内（配合 SEC-2-A 架构）或编辑器强制只读段（配合 B 架构） |

---

### ~~🧹 技术债 — SkillList.tsx 拆分~~ ✅ 已完成（2026-04-23）

> ~~`SkillList.tsx` 单文件 47K，远超 800 行上限。~~

| 子任务 | 说明 |
| --- | --- |
| ~~**DEBT-1**~~ SkillList.tsx 按功能拆分 | ✅ 1250 行拆成 11 个模块（types / utils / icons / FilterItem / SkillCard / SkillTable / SkillDrawer / NewSkillModal / SkillDraftPanel / SkillAbPanel / SkillEvolutionPanel），主文件降至 257 行；commit 0576213 |

---

### ⏸️ V2 推迟池

> 经 Analyst + Challenger 对抗评审后，以下项明确推迟至 V2 或等触发条件成立后重评。

| 项 | 推迟理由 | 重评触发条件 |
| --- | --- | --- |
| **P14 整体** | 无真实 τ-bench 级评测需求；UserSimulator 收敛难；pass^k token 账单高 | 真实业务场景需要多轮评测 |
| **P3-2** Memory Eval 模式 | 依赖 P14 基础设施 | P14-1~4 就位后 |
| **P3-4** 记忆质量闭环 | 依赖 P3-2 | P3-2 就位后 |
| **P15-3** GetEvalRunTool | Analyzer MVP 不需要；eval 数据量小，分析价值有限 | P15 MVP 验证有价值后 |
| **P15-4** GetAgentConfigTool | 与 P11-1 功能重叠 | P11 排期时合并实现 |
| **P15-6** 分析结果落库 + 前端 tab | 首版文本输出够用；工程量与首版价值不匹配 | P15 MVP 验证有价值后 |
| **P11-3** capabilities/tags | agent 数量 < 10，name 查找够用；tag 系统过度设计 | agent 数量 ≥ 20 时 |
| **P12-3** SystemJobRegistry | 首版保留 `@Scheduled` 注解不动 | 有 UI 可观测 system job 的真实需求 |
| **P12-6** 告警推送 + admin 权限分离 | 先让调度器跑起来 | 多端部署 / 多用户场景 |
| **P10-4** 自定义 slash command | 4 条命令覆盖 90% 用例 | 有明确自定义需求 |
| **P10-5 `/help`** | 4 条命令不需要 help menu | — |
| **P13-9** `/api/agents/*` rate limit | 单机 + token 认证下不是真实威胁 | 多端部署场景 |
| **P9-5** "活跃 skill 上下文 + pending tasks" 注入部分 | 文件摘要是核心，其他两项价值存疑；需 design doc 先行 | P9-4 上线收到数据后重评 |

---

### ~~P4 — 编码 Agent（Code Agent）~~ ✅ 已完成

> ~~目标：创建一个能编码的 Agent，通过绑定代码类 Skill 实现自主编写 Hook 方法，提升 Agent 自身能力（自举闭环）~~

已移至「已完成」表格。

---

### ~~P5 — 前端体验优化~~ ✅ 已完成

> ~~目标：修复当前 Dashboard 的交互痛点，补全缺失功能，提升整体使用体验~~

已移至「已完成」表格。

---

## 已完成

| 任务                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | 完成日期                                                                                                                                                                                                                                                                                                                    |            |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| fix(session-store) 持久化 `Message.reasoning_content`：OpenAI 兼容 provider（DashScope/Qwen/DeepSeek）thinking 模式下，assistant 带 tool_use 时下一轮必须原样回传 `reasoning_content`，否则 API 返回 HTTP 400；in-memory 链路已 OK（`OpenAiProvider` SSE 累积 → `LlmResponse` → `AgentLoopEngine.buildAssistantMessage`），但持久化 / 重载丢失。**V22 migration** 加 `t_session_message.reasoning_content TEXT NULL` 列；`SessionMessageEntity` 加 `reasoningContent` 字段（`@Column(name="reasoning_content", columnDefinition="TEXT")` + getter/setter）；`SessionService.appendRowsOnce` + `toStoredMessages` 两处单行补全读/写（所有 write / rewrite / compaction 收敛到 `appendRowsOnce`，改一处覆盖全部）；新增 `SessionServiceReasoningContentIT`（4 个 case：NORMAL with / NORMAL without / mixed batch / SUMMARY-via-appendRowsOnce round-trip）。Full Pipeline 跑通（Planner + Plan-Reviewer PASS，0 blocker / 5 warning / 3 nit → W1/W2 折进 Dev；Backend Reviewer PASS，0 blocker / 2 warning / 3 nit）。Phase 4 verify：`mvn -pl skillforge-server compile` 通过；310/310 non-IT 测试绿；Docker daemon 不可用跳过 IT（既有环境问题，`SessionRepositoryIT` 同症）；重启 server（zonky embedded PG 14.10）Flyway 成功应用 V22 + Hibernate `ddl-auto: validate` 通过 | 2026-04-24 | |
| 🔥 引擎稳定性（Compact Breaker 误触 + LLM Stream 抗抖，commit `121e8dc`）：**BUG-A** performed=false 不再累加 breaker（cec1cd60 根因），+ 额外修复 `FullCompactStrategy.callLlm` 吞异常 root cause（plan 漏的，Phase 3 reviewer 发现）；**BUG-B** 60s time-based half-open + user turn 入口无条件 `resetCompactFailures()`（LoopContext `compactBreakerOpenedAt` 字段 + `AgentLoopEngine.recordCompactFailure` helper）；**BUG-C** 5 处 compact-path catch 从 `log.warn(e.getMessage())` 升到 `log.error(msg, e)` 带 stacktrace（engine 3 处 + max_tokens compact + CompactionService broadcastUpdated）；**BUG-D** `LlmStreamHandler.onWarning(key,value)` default method + `TraceSpan.attributes` Map + OpenAiProvider SSE tool_use parse fail 发 4 个 warning key → engine 聚合写 LLM_CALL span attributes + 带 sessionId 的 log.warn；**BUG-E** OpenAiProvider/ClaudeProvider 构造器拆分 `httpClient`（non-stream）/ `streamHttpClient`（stream）两个 OkHttpClient；**BUG-E-bis** `chatStream` 握手前 ConnectException/SSLHandshakeException 短重试（2 次 / 2s→5s 退避 / ±20% jitter / `Call.execute()` 返回后一律不 retry 防 delta 重复）；application.yml 注释修正。Full Pipeline 运行（planner/reviewer agent 响应延迟 1h+ 导致主会话接手实施 + self-judge；但 reviewer round 1 仍发现 plan 和主会话都漏的 `callLlm` 吞异常深层 bug）。新增测试 12 个（AgentLoopEngineBreakerTest 9 + ProviderHttpClientSplitTest 2 + CompactionServiceTest rethrow 1）；mvn test -Dtest='!*IT' 364 / 0 failures | 2026-04-24 | |
| P8 记忆 LLM 提取（P8-1~P8-4 全部完成）：新增 `LlmMemoryExtractor` 组件，LLM 分析 session 对话历史输出结构化记忆条目（type/title/content/importance）；`ExtractedMemoryEntry` record 解析 LLM JSON 输出（含 markdown fence 剥离 + 类型/重要性校验）；`MemoryProperties` 配置类（`extraction-mode: rule\|llm`、`extraction-provider`、`max-conversation-chars`）；`SessionDigestExtractor` 按模式分流 + LLM 失败自动降级到规则式提取；prompt 模板含 5 类记忆分类（knowledge/preference/feedback/project/reference）+ 3 级重要性 + 已有记忆标题去重上下文；importance 编码在 tags（`auto-extract,llm,importance:high`）免 migration；19 个单元测试全绿（JDK proxy + 手动 stub 绕过 Java 25 Mockito 限制） | 2026-04-21 | |
| Session 批量删除：`DELETE /api/chat/sessions/{id}`（单删）+ `DELETE /api/chat/sessions` body `{ids}`（批删，上限 100）；悲观锁（SELECT FOR UPDATE）防 TOCTOU、WS 广播移到 AFTER_COMMIT 防幽灵通知、删前 null 子 session parentSessionId 防悬空引用、findAllById 消除 N+1；前端 SessionList 新增复选框列 + 批量操作栏（slide-in 动画）+ 行 hover 删除按钮；skipped running 会话保持选中状态；Full Pipeline（2 dev + 2 reviewer + judge） | 2026-04-21 | |
| Session 来源渠道可视化：`SessionEntity` 加 `@Transient channelPlatform` + `ChannelConversationRepository.findBySessionIdIn` 批量注入（active 行优先，default "web"）；前端新增 `ChannelBadge` 组件（chip/dot 两态），Chat crumb 展示渠道徽章（depth 0 自动隐藏）、ChatSidebar 非 web 行加色点、SessionList 新增 Channel 列 + 侧栏过滤器 | 2026-04-21 | |
| Context breakdown API + 实时 token 面板：新增 `GET /api/chat/sessions/{id}/context-breakdown` 走 `SystemPromptBuilder` + `TokenEstimator` 估算当前 context 各段占用；返回层级化 segments（system prompt 子段：agent prompt/SOUL/TOOLS.md/skills list/behavior rules/env/session context/user memories；tool schemas JSON；messages：conversation text/tool calls/memory results/other tool results）；分母用 `ModelConfig.lookupKnownContextWindow` 解析 agent 实际模型窗口；`MemoryService.previewMemoriesForPrompt` 分离出不 bump recall counts 的估算路径；Chat 右栏 Context tab 重做（堆叠条 + 可展开分段列表 + ±10% 精度说明），取代之前误导性的"累计消耗当当前窗口"卡片；reviewer 2 pass（java-reviewer + typescript-reviewer） → 应用 HIGH/MEDIUM 修复（Map/ContentBlock 双形状适配、ObjectMapper 化 tool_use input/tool_result content、aria-controls、role="img"、config null-safe）+ 低优先级修复（@Transactional readOnly、controller 传入已读 session 避免二次查、debug log 仅 class name、CSS var fallback、API 边界 null→undefined 归一化） | 2026-04-21 | |
| Session 详情 drawer 整体打磨：① Turns tab 重做为气泡风格（`U`/`A` 头像 + user 右 agent 左 + 圆角气泡 + tool 调用集成为带状态卡片，error 态红色，tool_result-only 的 user 消息收编到调用方不再独立显示）；② Context tab 接入 `/context-breakdown` 真实 API，取代硬编码 8.2K/12.4K + 391K/200K 的误导数据（详见 bug #22）；③ drawer title / tabs / body 三行左缘对齐（`.sf-drawer-tabs` padding 20→12，跨 Sessions/Eval/Skills/Hooks/Tools/Memory 全部受益）；④ `.sess-row` 加 `box-sizing: border-box` 修复 Tokens/Context/Cost/Last 列和 header 错位 | 2026-04-21 | |
| P7 飞书 WebSocket 长连接模式（P7-1~P7-7 全部完成，P7-8 E2E 验证已完成）：ChannelPushConnector SPI（`channel/spi`）+ FeishuWsConnector（OkHttp + ping/pong + 构造器注入 FeishuClient）+ FeishuWsEventDispatcher（WS 帧解析 → FeishuEventParser → ChannelSessionRouter + service_id ACK）+ FeishuWsReconnectPolicy（指数退避 1s→64s + ±20% jitter）+ ChannelPushManager（SmartLifecycle phase=DEFAULT_PHASE+100，start 扫 DB 建连，stop 最多等 3s）+ configJson mode 字段（websocket/webhook，patch 时返回 restart warn）+ 前端 ChannelConfigDrawer mode 切换 + websocket 重启提示；fix 提交加固 ping/pong + ACK 头保留 + ChannelPushManager 重启安全；新增 FeishuWsEventDispatcherTest + ChannelPushManagerTest 回归测试；技术方案：docs/design-feishu-websocket.md | 2026-04-21 | |
| P6 消息行存储重构（P6-1~P6-6 全部完成，P6-7 V2 预留）：核心不变量消息只增不删；V18 migration 新建 `t_session_message`（seq_no/role/content_json/msg_type/metadata_json）+ SessionMessageEntity + SessionMessageRepository；SessionService 新增 getFullHistory/getContextMessages/appendMessages 三方法（LLM 三段拼接：youngGen + summary + 新消息）；CompactionService 改为 INSERT COMPACT_BOUNDARY + SUMMARY 行不删旧消息；ChatService 改用 getContextMessages + appendMessages；SessionMessageStoreBackfill 历史数据迁移；前端 useChatMessages.ts 适配 COMPACT_BOUNDARY 渲染分隔线；CheckpointModal + branch/restore API；rollout toggle 脚本；技术方案：docs/design-p6-session-message-storage.md | 2026-04-21 | |
| P2 多平台消息网关（飞书 + Telegram，可扩展至微信/Discord/Slack/iMessage）：ChannelAdapter SPI（List<ChannelAdapter> Spring 自动收集，新平台零框架改动）+ V17 migration（5 张表：t_channel_config/conversation/message_dedup/user_identity_mapping/delivery）+ 3-phase 交付事务（claimBatch SKIP LOCKED + IN_FLIGHT 首次直接入库防 30s race）+ ChannelTurnContext 多轮回复关联（per-turn platformMessageId 防 unique constraint 冲突）+ MockChannelAdapter @Profile(dev,test) CI 友好 + 飞书 SHA-256 签名验证（encryptKey，非 HMAC）+ Telegram HTML parse mode + 4096 codepoint 安全拆分 + ChannelConversationResolver 独立 @Service 解决 @Transactional self-invocation bypass（HIGH-1）+ DeliveryTransactionHelper @Service 解决相同问题 + 前端 /channels 页面（平台卡片 + 会话列表 + 投递重试面板）+ 升级路径记录于 docs/p2-channel-plan-b.md §12.3；Full Pipeline（2 planner + 2 reviewer 多轮 + judge 裁判） | 2026-04-20 | |
| P1 Skill 自动生成 + 自进化（P1-1~P1-4 全部完成）：Session → SkillEntity 自动提取（LLM 识别可复用模式，批处理模式）+ Skill 版本管理（version/parentSkillId/usageCount/successRate 字段 + 版本回滚）+ Skill A/B 测试（复用 AbEvalPipeline，held-out 场景集对比，Δ≥15pp 自动晋升）+ 进化闭环（使用信号采集 → LLM 优化 SKILL.md prompt → A/B 验证 → 自动晋升/回滚） | 2026-04-20 | |
| P4 Code Agent（Phase 1-3 全部完成）：**混合 Hook Method 体系** — ScriptMethod（bash/node 脚本，即时生效）+ CompiledMethod（Java 类，需编译+人工审批）；Phase 1 CodeSandboxSkill（隔离沙箱执行 + HOME 环境变量沙箱化 + DangerousCommandChecker）+ CodeReviewSkill + ScriptMethod CRUD + BuiltInMethodRegistry 可变化 + V10 migration；Phase 2 DynamicMethodCompiler（javax.tools 进程内编译 + FORBIDDEN_PATTERNS 安全扫描）+ GeneratedMethodClassLoader（child-first 隔离）+ CompiledMethodService（submit→compile→approve 审批流）+ V11 migration；Phase 3 CodeAgentInitializer（9-skill pack 种子模板 + existsByName 防并发）+ HookMethods.tsx 前端页面（双 Tab script/compiled + grid/table + detail drawer + approval 操作）+ typed API functions；Review 修复：Instant 时间字段、TIMESTAMPTZ、@Lob 移除、registry/DB 原子性、FORBIDDEN_PATTERNS 扩充、temp path 脱敏、错误信息安全化、ARIA 无障碍、delete 确认弹窗 | 2026-04-19 | |
| P5 前端体验优化（Phase 1-6 全部完成）：**整体交互重构** — 从 Ant Design 默认样式迁移到自研设计系统（CSS custom properties token 字典 + feature-scoped CSS modules），参考 Linear/Raycast 开发者工具风格；全部 10 个页面重写（Skills/Tools/Traces/Sessions/Evals/Memory/Usage/Agents/Chat/Teams）；Phase 1 chat compaction banner + session 分页 + i18n；Phase 2 token 字典 + ThemeContext 暗色模式；Phase 3 用户输入样式 + Agent UX + empty states + loading/error；Phase 4 响应式侧边栏 + Traces 颜色编码 + a11y；Phase 5 Layout 重构 + ActivityRail + CmdK 命令面板；Phase 6 Traces BFS span 遍历 + input 预览标题 + Session drawer 真实消息 + Skills system/custom 分类 + Agent hook handler 类型感知自动补全 + Tool 描述截断 + Hook tab 配置扩展 + Chat RightRail SubAgent tab + 顶部导航 Memory 标签修正 | 2026-04-19 | |
| N3 P2 Lifecycle Hook Method 体系：BuiltInMethod 接口 + BuiltInMethodRegistry（HttpPost/FeishuNotify/LogToFile 三个内置方法）+ MethodHandlerRunner（arg merging）+ UrlValidator（InetAddress SSRF 防护 + IPv6/link-local 拦截）+ 静态 HttpClient 防线程池泄漏 + header injection denylist + 异常信息脱敏 + ConcurrentHashMap 文件锁 + LifecycleHookService 抽取（dry-run + hook-history）+ HookHistoryDto DTO 投影 + 前端 MethodHandlerFields（方法下拉 + args 表单 + loading 状态穿透）+ DryRunResultModal + HookHistoryPanel 时间线 + Traces LIFECYCLE_HOOK 可视化 + API 类型安全（消灭 4 个 any）+ getLifecycleHookMethods 响应解析 bug 修复 + React key 去重 + 18 项 review fix；Full Pipeline（2 reviewer + judge + fix）；202 后端测试全绿 | 2026-04-17 | |
| N3 P1 Lifecycle Hook 完善：多 entry 链式执行（dispatcher for 循环 + ChainDecision 三值 + SKIP_CHAIN policy）+ ScriptHandlerRunner（bash/node 子进程 + /tmp toRealPath() symlink 防护 + ProcessHandle.descendants 进程树 kill + 双线程 drain-and-discard 防 OOM + env 5 白名单 + DangerousCommandChecker 绝对路径 pipe 防护）+ UserPromptSubmit prompt enrichment（handler output.injected_context 注入独立 user message 支持全 provider）+ 前端多 entry UI（上移/下移/删除/新增 cap 10 + stable \_id key 防 stale debounce）+ Script handler 启用（Confirm Modal + lang 硬编码 [bash,node] + 字符计数）+ Forbidden Skill 黑名单（dispatcher 层）+ async×SKIP_CHAIN 保存时拒绝 + scriptBody 长度后端校验 + FailurePolicy @JsonCreator 防回滚炸 + discriminated-union 类型安全 + CSS var(--color-error) + 全 provider 支持；2 planner + 2 reviewer + judge + 2 fix pipeline；145 后端测试 + agent-browser e2e 全绿；技术方案：docs/design-n3-p1.md | 2026-04-17 | |
| N3 P0 用户可配置 Lifecycle Hook：V9 migration + polymorphic HookHandler (skill/script/method 子类，P0 只实现 SkillHandlerRunner) + LifecycleHookDispatcher（hookDepth ThreadLocal 跨 worker 线程传播 + 独立 hookExecutor + timeout + failurePolicy CONTINUE/ABORT + LIFECYCLE_HOOK TraceSpan）+ SessionStart 插在 ChatService.chatAsync 首条消息处 / SessionEnd 异步在 loop 结束 + AgentLoopEngine ABORT 语义 (LoopContext.abortedByHook + HookAbortException) + REST `/api/lifecycle-hooks/events\|presets` + 前端 3 模式编辑器（Preset/Form/JSON + Zod discriminatedUnion + useDebouncedCallback + formKey 解决 create→create 复用）+ AgentSchema 加 lifecycleHooks/behaviorRules 字段防 Zod strip + 25 测试全绿 + agent-browser e2e 验证通过；3 轮 reviewer (java/typescript/security) + judge 仲裁 + fix 两侧并行；技术方案：docs/design-n3-lifecycle-hooks.md | 2026-04-17 | |
| N2-1~N2-3 Agent 行为规范层：V8 Flyway migration（behaviorRules TEXT 列）、BehaviorRuleRegistry（15 条内置规则 JSON + 语言检测 + deprecated 链 + 预设模板）、BehaviorRuleDefinition record、SystemPromptBuilder 注入（Available Skills 之后 + 自定义规则 XML 沙箱 + prompt injection 防护）、AgentYamlMapper round-trip（corrupt data 防御）、REST API（GET /api/behavior-rules + /presets）、前端 BehaviorRulesEditor（模板选择器 + 分类折叠 + Switch + 自定义规则 CRUD）、useBehaviorRules Hook、AgentList.tsx RULES.md Tab 集成；技术方案：docs/design-n2-behavioral-rules.md | 2026-04-17 | |
| N1-1~N1-4 Memory 向量检索：V7 Flyway migration（pgvector + tsvector，graceful fallback）、EmbeddingProvider + OpenAiEmbeddingProvider（/v1/embeddings）、EmbeddingService（降级）、MemoryEmbeddingWorker（@Async afterCommit）、MemorySearchSkill（FTS + pgvector RRF 混合检索）、MemoryDetailSkill（按需全文）、VectorUtils/SkillInputUtils 工具类提取；MemorySkill 移除 search action；Full Pipeline 审查修复：pgvector graceful degradation、afterCommit race fix、no-op sentinel bean、error sanitization、dimension validation | 2026-04-16 | |
| P2-6 Session → Scenario 转换：LLM 批量分析历史 session → draft scenarios → `t_eval_scenario`（V5 migration）；ScenarioLoader 同时加载 DB active 记录；ScenarioDraftPanel Review UI（Approve/Edit/Discard）；ScenarioLoader 改造 | 2026-04-16 | |
| P2-1~P2-5 Self-Improve Pipeline Phase 2：PromptVersionEntity（V4 migration）、PromptImproverService（LLM 生成候选 prompt）、AbEvalPipeline（held-out 集 A/B 对比）、PromptPromotionService（Δ≥15pp 自动晋升 + 4 层 Goodhart 防护）、前端 ImprovePromptButton + PromptHistoryPanel + rollback/resume | 2026-04-16 | |
| #5/#6 Self-Improve Pipeline Phase 1 实现：13 个场景 JSON（7 seed_ + 6 train_）；EvalExecutorConfig + evalOrchestratorExecutor（双独立线程池防死锁）；AttributionEngine（7×5 矩阵）；EvalRunEntity + EvalSessionEntity + V3 Flyway；SandboxSkillRegistryFactory + EvalEngineFactory（无 compactorCallback/pendingAskRegistry）；ScenarioRunnerSkill（3级重试 90s 预算）；EvalJudgeSkill（2×Haiku + Sonnet meta）；EvalOrchestrator（Goodhart 防护 + Δ 监控）；REST API POST/GET /api/eval/runs；/eval 前端页面（实时 WS + 详情 Drawer）；Full Pipeline 评审修复：executor 死锁、rate limit ghost run、maxLoops 覆盖、5 个字段名错误 | 2026-04-16 | |
| #5/#6 Self-Improve Pipeline 完整方案设计（Plan A + Plan B + 双 Reviewer + Judge 全流程）；详见 docs/design-self-improve-pipeline.md | 2026-04-16 | |
| Collab-3 CollabRun WS 广播：ChatWebSocketHandler 注入 repo 查 userId，4 个 collab 事件改写为 userEvent 广播；Teams.tsx 订阅 /ws/users/1 实时 invalidate TanStack Query | 2026-04-16 | |
| Compact-2 CompactionService 解锁 LLM 调用：3-phase split — Phase 1 guard/prepareCompact (stripe lock) → Phase 2 applyPrepared (LLM, no lock) → Phase 3 persist (stripe lock + tx)；fullCompactInFlight Set 防并发重入 | 2026-04-15 | |
| Context-1 动态 Context Window：ModelConfig 静态模型表 + CompactionService 3 级解析链，废弃硬编码 32000 | 2026-04-15 | |
| #9 认证鉴权 MVP：auto-token on startup，Login 页自动预填，Bearer 拦截器 + WS 握手鉴权 | 2026-04-15 | |
| #20 API 响应格式统一 + extractList 防御代码集中 | 2026-04-15 | |
| #19 前端测试基础建设：Vitest 单元测试 + Playwright E2E | 2026-04-15 | |
| #18 前端安全加固：Zod schema 校验 + XSS/CSRF 防护 | 2026-04-15 | |
| #17 后端集成测试：Testcontainers 覆盖核心 Repository | 2026-04-15 | |
| #16 后端 Java 现代化：DTO 改用 records | 2026-04-15 | |
| #15 Chat 交互状态升级：hover / focus / active / loading | 2026-04-15 | |
| #14 Dashboard 视觉升级 | 2026-04-15 | |
| #13 长列表虚拟滚动：Traces / Sessions / Teams | 2026-04-15 | |
| #12 前端引入 TanStack Query 替换手动 useEffect | 2026-04-15 | |
| #11 Chat.tsx 重构：拆分 28 个 useState | 2026-04-15 | |
| #10 Context Window Token 按 Provider 动态配置 → 合并入 P1-1 | 2026-04-15 | |
| Teams 页面（多 Agent 协作可观测 UI） | 2026-04-15 | |
| ObjectMapper 全项目修复（10 处补 JavaTimeModule） | 2026-04-15 | |
| @Transactional 修复（CollabRunService、SubAgentRunSweeper） | 2026-04-15 | |
| H2 → PostgreSQL 迁移（embedded zonky PG，port 15432） | 2026-04-15 | |
| EmbeddedPostgresConfig 重启修复（setCleanDataDirectory） | 2026-04-15 | |
| Flyway schema 管理（ddl-auto: validate） | 2026-04-15 | |
| .claude/ 规则/Agent/命令集成（ECC + design.md） | 2026-04-15 | |
| #4 Commit 所有积压改动 | 2026-04-15 | |
| #8 Chat 稳定性：ErrorBoundary + WS 断线重连（指数退避） | 2026-04-15 | |
| #7 Memory 调度：session 完成后立即异步提取，补 @EnableAsync | 2026-04-15 | |
| P13-2 Hook 编辑器重写（schema-aware）：AgentDrawer Hooks tab 接入 LifecycleHooksEditor + useLifecycleHooks；discriminated union + entry-level 字段全部恢复；liveRawJson + baselineJson 准确 dirty 检测；migrateLegacyFlat 处理老拍平 JSON；38 tests、浏览器 e2e 验证 PUT round-trip 稳定 | 2026-04-22 | |
| P13-5/7/8/10/11 follow-up batch：TS 错修复 / AgentNotFoundException / updateAgent 审计日志 / 后端驱动 model options / Tool-Skill 语义对齐 + Role 可编辑 + 多模型扩展 | 2026-04-22 | |
| P9-1 Compactable 工具白名单：CompactableToolRegistry（默认白名单 11 个工具）+ LightCompactStrategy Rule 1 白名单过滤 + per-agent config 支持 + 25 个测试全绿 | 2026-04-22 | |
| P9-3 Time-based 冷清理：TimeBasedColdCleanup + LoopContext.sessionIdleSeconds + AgentLoopEngine 首次迭代触发；默认 5 分钟阈值 / 保留最近 5 个；12 个测试全绿 | 2026-04-22 | |
| P9-6 Session Memory 零 LLM 压缩：SessionMemoryCompactStrategy（零 LLM）+ CompactionService Phase 1.5 优先尝试 memory compact，失败降级 LLM；8 个测试全绿 | 2026-04-22 | |
