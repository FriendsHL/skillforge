# Tech Design — AUTOEVOLVE-AGENT-FLYWHEEL

> 状态：待 architect review。承接 [prd.md](prd.md)。本文含本次对话中 architect 预审已坐实的发现（file:line）+ 风险 + 测试计划。

## 0. 关键架构原则（贯穿全包）
1. **orchestrator 顶层运行,非 workflow 子 agent** → A/B-as-tool 只 fan-out 2 层（orchestrator → abEvalLoopExecutor），跟现有飞轮触发 A/B 一致,**无 3 层递归陷阱**（architect 确认：若放 workflow 子 agent 内会 ~98 个嵌套 engine.run 跨 3 pool）。
2. **确定性计算 vs LLM 工作分层**：归因/improver = LLM（agent/tool wrapping one-shot chat）；A/B/promote = 确定性算力（包 tool 复用,不重写）。
3. **复用评测引擎,新增只在编排 + tool + 可观测**。

---

## 模块 A — Workflow 操作能力（RunWorkflow tool）

### 技术方案
- 新 `RunWorkflowTool implements Tool`，注入 `WorkflowRunnerService` + `WorkflowDefinitionRegistry`。
- **name 模式**：`runnerService.startRun(name, args, userId)`（现成,line 117）。
- **inline 模式**：`registry.parseInline(source)`（**新**：把现有私有 `parse(fileName, source)` 重构出公开方法）→ `runnerService.startRun(def, args, userId)`（**新重载**：跑 WorkflowDefinition 直接,不走 registry 查找；复用现有 run 机器：consolidation lock / anchor session / FlywheelRun / evaluator.evaluate；lock 用 inline 的 meta.name）。
- 返回 runId（异步 fire-and-trigger）。
### 安全
- 注册进**主 SkillRegistry**（SkillForgeConfig），**绝不进 WorkflowSkillRegistryFactory**（子 agent registry，line 53-61 仅 6 OPT-REPORT tool）→ 子 agent 拿不到 → 防递归。
- inline 跑同一 L1 sandbox（ClassShutter / 无 Packages-eval / scoped registry / instruction cap / 1000 agent-call cap / 30min）。
- 不默认进任何 agent tool_ids。
### 复用 / 新建
复用 WorkflowRunnerService / Registry / L1 sandbox；新建 RunWorkflowTool + parseInline + startRun(def) 重载。

---

## 模块 B — A/B 封装成 Tool

### 现状（architect 坐实）
- `PromptImproverService.runAbTestAgainst` **异步**（建 abRun 行 → `registerSynchronization(afterCommit)` 把 compute deferred 到 coordinatorExecutor，`PromptImproverService.java:714-720`，注释 704-713 说明 deferral 是避 listener 抢 commit）。
- 底层 `AbEvalPipeline.run` / `runWithScenarios` **同步**（`AbEvalPipeline.java:308-453`，`CompletableFuture.allOf(...).join()` line 396）。
- **prompt**：per-scenario baseline+candidate `{status,score}` 都存（`AbEvalPipeline.java:376-381` + `PromptAbRunEntity.abScenarioResultsJson:50`）→ per-task 可导。
- **skill**：baseline 侧硬编码 `new RunResult("UNKNOWN", 0.0)`（`SkillAbEvalService.java:688-691`）→ **per-task 不可用**（E2 前置）。
- promote 守卫：prompt `evaluateAndPromote` 有 delta≥15pp / promoted-today / 24h cooldown / paused（`PromptPromotionService.java:58-85`）；skill `promoteCandidate` V64 partial-UNIQUE ordering（`SkillAbEvalService.java:839-861`）。

### 技术方案（agent 驱动 = 异步 + 轮询,不造同步 wrapper）
- **`TriggerAbEval`**：调现有异步 `runAbTestAgainst`（surface 分派到 Skill/Prompt/BehaviorRule service），返 abRunId。
- **`GetAbResult`**：查 abRun 行状态 → terminal 返 {baselineScore, candidateScore, delta, perScenario(prompt 有)}。
- **`PromoteCandidate`**：调现有 promote service,**走守卫**（不绕）。
- **baseline 缓存（B4）**：TriggerAbEval 若目标 agent 有近期 COMPLETED baseline ab_run 则复用其分数,跳过 baseline 侧重跑。
### 复用 / 新建
复用 runAbTestAgainst / AbEvalPipeline / ab_run 实体 / promote service；新建 3 tool（薄包装）+ surface 分派。

---

## 模块 C — Auto-Evolving Orchestrator

### 现状（architect 坐实）
- improver **纯 service**（one-shot `LlmProvider.chat()`，`PromptImproverService.java:1083,1096`；`SkillDraftService` 直接用 LlmProvider）,由 `AttributionApprovalService.approve` 调（line 444-486）。**无 improver agent**。
- 唯一真 agent 是 attribution-curator（产 proposal）。
- 现有 auto-trigger 全局 flag `@ConditionalOnProperty(...auto-trigger-ab-on-candidate-ready, matchIfMissing=true)`（`OptimizationEventAutoTriggerListener.java:67-70`，默认 true，`application.yml:116`）→ false 时 bean 不建 → 无事件。
- A/B 冲突守卫：`PromptImproverService.java:617-623` / `SkillAbEvalService.java:210-215`（active A/B 存在 → ImprovementConflictException，防 corruption 但仍 divergence）。

### 技术方案
- **orchestrator agent**（t_agent + tool_ids + loop prompt，顶层运行）。
- **`GenerateCandidate` tool**：包装 improver service 的 one-shot 生成（不套 agent loop——LLM-fill 已是确定性 one-shot，包 tool 即可），返 candidateId。
- **loop**：orchestrator prompt 驱动——"拿 report → for each issue：GenerateCandidate → TriggerAbEval → GetAbResult 轮询 → 科学 gate 判断 → RecordIteration → 下一个 / 重产 report → 直到 maxIter / 不再提升"。
- **末尾 humanApprove**：复用现有 ask_user / humanApprove 卡片机制让人定夺；采纳走 PromoteCandidate。
- **C6**：dogfood 期 `auto-trigger-ab-on-candidate-ready=false`（关旧编排）。
### 复用 / 新建
复用 improver service / opt-report workflow（via RunWorkflow）/ promote / 全局 flag；新建 orchestrator agent + GenerateCandidate tool + loop prompt。

---

## 模块 D — 迭代账本 + 轨迹可观测

### 技术方案
- **迭代账本**：新表 `t_evolve_iteration`（evolveRunId / iteration / targetAgentId / surface / changeDesc / candidateId / baselineScore / candidateScore / delta / kept / predictedImpactJson? / createdAt）。或挂 FlywheelRun + steps（评估复用 vs 新表,architect 给意见）。
- **`RecordIteration` tool**：orchestrator 每轮落账。
- **轨迹页**：折线图(分数 vs iteration)+ 每点 hover 显改动。复用 /autoevolving + reactflow/echarts。多 evolve run 叠加。
### 复用 / 新建
复用 ab_run 已存单次分数 / /autoevolving 页框架 / 图表库；新建账本表 + RecordIteration tool + 轨迹页。

---

## 模块 E — AHE 借鉴（进阶）

### 技术方案
- **predicted_impact 字段**：候选实体（t_skill_proposal / prompt_version / behavior_rule_version）加 `predicted_impact_json`（per-task flip 预测）。
- **per-task 对比**：prompt 复用现有 per-scenario；**skill 先补 per-scenario baseline 存储**（现 `{"UNKNOWN",0.0}`）。
- **证伪**：实际 vs 预测逐 task → 失败 rollback/revise。
### 复用 / 新建
prompt per-scenario 复用；新建 predicted_impact 字段 + 证伪逻辑 + skill per-scenario baseline。

---

## 风险 + architect 预审发现（重点）

| # | 风险 / 发现 | 处置 |
|---|---|---|
| R1 | **递归陷阱**：A/B-tool 在 workflow 子 agent 内 → ~98 嵌套 engine.run 跨 3 pool | **orchestrator 顶层运行**（非 workflow 子 agent）→ 2 层,同现有飞轮。**硬约束** |
| R2 | **skill per-task baseline 缺**（硬编码 UNKNOWN）| E2 前置；v1 prompt-only 证伪 |
| R3 | **24h promote 守卫** → 一轮最多 promote 1 个 | "保留"= 候选记账,真 promote 末尾人定夺走守卫,接受单 promote/run（不绕安全栏）|
| R4 | **carry-forward per-task 不安全**（LLM 非确定,baseline 漂移 44.9→34.7,`AbEvalPipeline.java:661-667` 强制 temp=0）| aggregate 顺延可当估计；per-task 证伪 promote 后**重测一次新 baseline** |
| R5 | **两套编排并存** race | 关全局 auto-trigger flag（dogfood）；冲突守卫兜底（ImprovementConflictException 非 corruption）|
| R6 | **budget.remaining() JS binding 不存在** | loop 终止用 issue 试完 + JS maxIter 计数；引擎 agent-call cap(1000)/30min 兜底 |
| R7 | A/B 同步 wrapper 事务风险 | **不造同步 wrapper**（agent 驱动用异步 + 轮询绕过）|
| R8 | GenerateCandidate 套 agent loop 浪费 | improver 是 one-shot chat → 包 tool 不套 agent loop |

## 测试计划
- **模块 A**：RunWorkflowTool 单测（name/inline/校验）+ parseInline 测 + 子 agent registry 隔离测（断言 RunWorkflow 不在 WorkflowSkillRegistryFactory）。
- **模块 B**：TriggerAbEval/GetAbResult/PromoteCandidate 单测（mock service）+ baseline 缓存命中测 + promote 守卫拒绝测。**不依赖 mimo**。
- **模块 C**：orchestrator loop 逻辑测（mock tool）+ 真活 e2e（真目标 agent 跑一次完整 loop，需真 LLM）。
- **模块 D**：账本落行测 + 轨迹页渲染测（vitest）+ 多 run 叠加测。
- **模块 E**：predicted_impact 落字段测 + prompt per-task 证伪测。
- **核心 e2e**：真 agent 上跑完整 loop,轨迹图确认分数提升。

## 分期（实现顺序）
1. **第一波**：~~A（RunWorkflow）~~ **已造好,仅验证+分配+提交** + B（A/B tools）— 地基。
2. **第二波**：C（orchestrator + GenerateCandidate + loop + **触发入口**）— 串起 A+B+opt-report。
3. **第三波**：D（**复用 FlywheelRun+Step** + 轨迹图）— 可观测验证。
4. **第四波**：E（AHE predicted_impact + 证伪）— 进阶。

---

## 🔍 architect review 修正（2026-05-31，"别重复造轮子"核查）

### REUSE TABLE（新建项 vs 已有机器）

| 计划"新建"项 | 裁决 | 证据（file:line）|
|---|---|---|
| RunWorkflow tool | **已造好,别重建** | `RunWorkflowTool.java:52`(name+inline+resume)+ 注册 `SkillForgeConfig.java:449-458` + `parseInline` `WorkflowDefinitionRegistry.java:139` + `startRun(def)` `WorkflowRunnerService.java:142` + `RunWorkflowToolTest.java`。**模块 A 已 ship（工作树未提交）** |
| TriggerAbEval | 新 tool,包装现有（对）| 复用 `runAbTestAgainst:446` / `startAbTestFromDraft:785` / `startAbForVersion`；**抄 auto-trigger listener 的 surface 分派 switch**（`OptimizationEventAutoTriggerListener.java:154,177,199`）|
| GetAbResult | 真新 tool,数据源已有 | ab_run 行 + per-scenario JSON 已存;wrapper 读 repo |
| PromoteCandidate | 新 tool,包装现有守卫 promote（对）| `PromptPromotionService.java:56-85` 守卫 |
| GenerateCandidate | 新 tool,包装现有 improver（对）| improver service-only 无 agent → 包 tool 对 |
| **orchestrator agent** | **部分已有——extend `attribution-dispatcher`,别从零** | `AttributionDispatcherService.java:255-378` 已是 LLM 编排 dispatch loop（`attribution-dispatcher` agent walk `listAndReserveCandidates` + SubAgent 派发）= 正是"顶层 orchestrator 驱动 loop"的模式。新 orchestrator 是不同 loop（report→候选→A/B→gate→记账）故新 agent 合理,但**应仿 attribution-dispatcher + 复用 FlywheelChainOrchestrator 链** |
| **迭代账本 t_evolve_iteration** | **多余——复用 FlywheelRun+Step** | `FlywheelRunEntity` 已有 loop_kind/input_json/summary_json/status/agentId;`FlywheelRunStepEntity` 已有 step_index + 自由 schema `step_output_json:114` + status。一轮 = 一个 step（step_kind=`evolve_iteration`,delta/kept/分数进 step_output_json）。**或** `OptimizationEventEntity:74-100` 已建 iteration→A/B→score→promote stage 机器。**新表重复造已有两遍的列**——用户"别造轮子"命中 #1 |
| 轨迹页 | 图表真新,但壳+数据复用 | `FlywheelFlowchart` 是固定拓扑 DAG 非 score-over-time → 折线图真新;但 `SkillEvalHistoryEntity:38-51`（composite+4 维+时间戳/run）= score-over-time 表,复用;/autoevolving + FlywheelObservability 壳复用;**唯一净新基建 = 加图表库**（现 flywheel 组件无 echarts/recharts）|
| RecordIteration tool | 新 tool,若复用 Step 则极薄 | 复用 `FlywheelRunService` step-append |
| predicted_impact + 证伪(E)| **真新** | grep 确认仅在文档无代码;skill per-scenario baseline 缺真存在 |

### 安全补充（review 发现）
- **inline meta.name 碰撞 / 命名欺骗 DoS**（原计划漏）：inline `startRun(def)` lock 用 `def.name()`，agent 可写 `meta.name:"opt-report"` → `WorkflowAlreadyRunningException` 卡真 opt-report,或冒名跑。**缓解**：inline run 命名空间前缀（`inline:`）或拒绝 inline meta.name 撞已注册名。
- **B tools 也要排除出 WorkflowSkillRegistryFactory**（原计划只对 A 说）：子 agent 拿到 TriggerAbEval 会重开 fan-out 递归路径。
- **服务端 per-evolve-run A/B 预算上限**（原计划只有 prompt 级 maxIter）：prompt 是不可信面,引擎 1000-call/30min cap 对真 A/B 算力太松 → 加 TriggerAbEval per-evolve-run 硬上限。
- **PromoteCandidate 校验候选属于目标 agent**：守卫 gate"是否 promote"不 gate"哪个候选合法",prompt bug 可能传别的 agent/surface 的 candidateId → tool 透传 target-agent + service 校验归属。

### 漏项（补进模块）
- **C 触发入口**（谁启动 orchestrator session）：原计划无 FR。仿 `POST /api/flywheel/agents/{agentId}/run-loop`（`FlywheelChainOrchestrator.java:31-32` + `AttributionDispatcherService.dispatchOne:566-572` 建 system session + chatAsync）。**补 FR-C0**。
- **RecordIteration 时序 vs C 的 e2e**：AC-C"记账"需要 RecordIteration（在 D），否则 C 首次 e2e 记空。**把 RecordIteration 移进 C**。

### 单条最重要调整
**开发前用 shipped code 重新校准"复用 vs 新建"清单**：A 已 100% 完成（别重建,只分配+验证 + 提交）；D 账本复用 `t_flywheel_run`+`t_flywheel_run_step`；C orchestrator 仿 `attribution-dispatcher`。真新工作收敛到：4 个 B/C/D 薄 tool + 1 orchestrator prompt + 1 轨迹图 + 模块 E。
