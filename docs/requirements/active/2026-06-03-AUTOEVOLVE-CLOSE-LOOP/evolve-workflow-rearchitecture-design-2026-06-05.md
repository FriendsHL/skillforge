# AUTOEVOLVE Evolve Loop 架构重构设计文档

> 状态：设计提案（architect 产出，未写实现，**待 user review**）。日期 2026-06-05。
> 关联：`conclusions-and-direction-2026-06-05.md` §3。
> 目标：把 evolve loop 的**外层编排**从「单 agent 驱动」（脆弱、今晚所有故障的根因）改成「确定性 `evolve-loop.workflow.js` 编排 + LLM 叶子节点」，对齐已稳定的 opt-report 样板模式。

---

## 0. 一句话结论 + 命门答案先行

把 orchestrator agent 退役，外层 loop 改成 `evolve-loop.workflow.js`（跑在现有 `WorkflowRunnerService`）。**但有一个必须先解决的引擎 gap**：

> **关键技术问题的确定结论**：当前 workflow DSL 的 host binding 只有 `agent / parallel / pipeline / phase / log / humanApprove / ctx / args`（见 `HostBindings.register`）。**没有任何 binding 能让 `*.workflow.js` 直接调一个 Java tool 或 Java service**。所有"机械步骤"在 opt-report 里都是塞进 `agent()` 的 prompt 让 LLM 去 tool_use。而且那 6 个 evolve tool 被**刻意**排除在 `WorkflowSkillRegistryFactory` 之外（recursion guard），所以连"让叶子 agent 调它们"这条路当前也堵死。

因此本设计核心取舍：**Phase 1 必须给 DSL 新增一个确定性 `tool()` host binding（直接在 workflow 线程上同步调 service/tool，落一条 step，不 spawn agent）**。这是"机械步骤 = 直接 tool 调用、不包 agent"落地的唯一干净方式。§4 给出完整设计 + 退路。

---

## 1. 现状取证（已核实）

| 事实 | 证据 | 设计含义 |
|---|---|---|
| orchestrator 是 top-level agent，`EvolveController.run` spawn 它 + chatAsync | `EvolveController.java:194-210` | 退役点：spawn-agent → `startRun("evolve-loop",...)` |
| opt-report 是 `*.workflow.js`，确定性放 JS、LLM 工作放 `agent()` | `opt-report.workflow.js` | 目标样板 |
| DSL host binding = `agent/parallel/pipeline/phase/log/humanApprove/ctx/args` | `HostBindings.register` | **没有 tool()/service() binding —— 命门** |
| 每个 `agent()` 落一条 `subagent_dispatch` step（step_input 含 prompt，step_output 含 finalResponse+subSessionId） | `DefaultWorkflowAgentInvoker.invoke` | 现成可追踪基座；缺语义 delta 位置 |
| `humanApprove()` 落 step + pause/resume（journal-replay 对齐 stepIndex） | `HostHumanApprove`+`WorkflowRunnerService.resume` | evolve 末尾交人定夺直接复用 |
| 6 个 evolve tool 薄包装→service，且被排除在 workflow 子 agent 注册表外 | tool javadoc "deliberately ABSENT" | 复用其 **service**，不复用 tool 外壳 |
| workflow run = `FlywheelRun(loop_kind=workflow)`；evolve run = `loop_kind=evolve` | `WorkflowRunnerService`/`EvolveController` | **冲突点** 见 R1 |

---

## 2. 节点图（evolve-loop.workflow.js 的 DAG）

三类节点，严格按"节点类型铁律"：🟦 JS 确定性编排（不 spawn）/ 🟩 tool 节点（直接调 service，落 step，不 spawn agent）/ 🟨 agent 叶子（LLM 判断，有 session+trace）。

```
evolve-loop.workflow.js   (args: targetAgentId, maxIter, reportId?, autoApprove)
│
├─🟦 phase('Report')
│   └─ reportId 缺失 → 🟩 tool('RunOptReportSubflow', {agentId, autoApprove:true})
│      否则           → 🟩 tool('GetOptReport', {reportId, expectedAgentId}) → report{topIssues[]}
│
├─🟦 JS: issues = selectAndRank(report.topIssues)   // Phase1 写死排序；Phase3 可换 agent 叶子
│
├─🟦 for (iter=1; iter<=maxIter && issues.length; iter++)   // 确定性循环，无 maxLoops
│   ├─🟨 agent('evolve-candidate-gen', schema:CAND_SCHEMA)   // LLM 叶子：读 issue+当前 best→产候选
│   │     → {candidateId/bundle, surface, changeDesc, prediction}
│   │     ⮑ emit semanticDelta {before→after, diff}
│   ├─🟩 tool('TriggerAbEval', {...}) → {abRunId}
│   ├─🟩 tool('GetAbResult', {...})   // service 已阻塞 90s
│   │     └─🟦 JS while status==='running' → 再 tool('GetAbResult')   // 确定性轮询，非 agent 轮询
│   │     → {baselineScore, candidateScore, delta, wouldPromote, perScenario}
│   ├─🟩 tool('ReconcilePrediction', {prediction, abResult}) → {hits, misses, riskHits, confidence}
│   ├─🟦 JS: kept = decideKeep(delta, deltaPassRate)   // 确定性双标准阈值
│   ├─🟩 tool('RecordIteration', {...全字段...}) → {stepId}
│   └─🟦 JS carry-forward: kept ? best=candidate : 守住 best
│
└─🟨/🟦 phase('Adopt')
      autoApprove ? return summary : humanApprove({keptBundles, trajectory})
      // 实际 adopt 仍走人类 POST /runs/{id}/adopt（Iron-Law 人类闸门）
```

| 节点 | 类型 | 为什么 |
|---|---|---|
| loop/排序/轮询/carry-forward/keep-reject | 🟦 JS | 编排，绝不给 agent（今晚炸点） |
| 候选生成 | 🟨 agent 叶子 | 创造力刚需，要 session+trace |
| issue 选择/排序 | 🟦 JS（P1）→ 可选 🟨（P3） | P1 写死规避不确定性 |
| GetOptReport/TriggerAbEval/GetAbResult/Reconcile/RecordIteration | 🟩 tool 节点 | 纯确定性，包 agent 是浪费 |

### 决策记录（user, 2026-06-05）：候选生成 = agent 叶子（option 2），保留退回 tool 节点的逃生门

- **选 agent 叶子**：要可追踪（甩节点 session 给我）+ 反思爬坡能多步推理。代价是比 tool 节点慢 ~30-60s/轮（多 1-2 个 agent LLM 轮次），但对 ~19min 整轮几乎无感。
- **为什么安全可逆**：`candidateId` 始终由 GenerateCandidate **service 持久化后返回**（叶子只转发、伪造不了），下游 tool 串的是真 ID；唯一 LLM 产、往下流的结构化字段是 `surface`/`prediction`，已被 ① schema-forced 输出（StructuredOutput + 重试）② A/B reality 对账（预测错只表现为 confidence 低，不破坏 loop）双重兜底。
- **回退触发条件（measurable，别凭感觉）**：候选叶子若出现以下任一**持续**现象 → 该面退回 tool 节点 🟩（`tool('GenerateCandidate',...)`）：
  - `schemaAttempts > 2` 成为常态（叶子反复吐不出合法 {surface, changeDesc, prediction}）
  - 候选叶子产出 malformed / 无效候选率 > ~20%
  - 叶子轮次异常膨胀（单次候选生成 > ~5 轮 = 又在 thrash）
- 因 ID 来自 service，**单面退回不影响其它面**，两种节点可混用。

---

## 3. 可追踪性机制

### 3.1 每节点落 step —— 复用 + 补一个 step_kind
现状 step 落账覆盖 agent 叶子（`subagent_dispatch`）+ human_approve，**缺 tool 节点（🟩）的 step**：
- 新增 step_kind = `tool_call`（`FlywheelRunStepEntity` 加常量；`step_kind` 是自由 String 列，若有 CHECK 约束需 database-reviewer 确认）。
- 新 `tool()` binding 在调 service 前后落：pending → completed{result,durationMs} / error{msg}，与 `DefaultWorkflowAgentInvoker` 同构。
- stepIndex 走 `ctx.nextStepIndex()`（与 agent()/humanApprove() 共用计数器），journal-replay 对齐不破。
→ workflow 每个节点（JS phase/tool/agent/approve）在 `t_flywheel_run_step` 都有行，FE DAG 一条不漏。

### 3.2 语义 delta（before→after）—— 引擎不白给，主动 emit
**存哪**：写进对应 step 的 `step_output_json.semanticDelta` 子键，**不新增表**。
```
semanticDelta = { surface, before:"<旧文本>", after:"<新候选文本>", diff:"<unified diff>", changeDesc }
```
**谁 emit**（Phase 1 选 a）：
- **(a) JS 侧组装（推荐）**：候选叶子返回 `{candidateId, surface, changeDesc}`；JS 用只读 tool `GetCandidateDiff` 查 before/after 文本塞进 step。**确定性、省 token、不靠 LLM 吐全文**。
- (b) 叶子直接吐全文：长文本进 LLM 输出 = 烧 token + 可能截断，不推荐。
**Phase 1 落点**：语义 delta 至少覆盖候选生成节点；其它节点（A/B 分数、reconcile 命中）本就结构化数字，进 RecordIteration 的 step_output_json 即可。

### 3.3 agent 叶子 session 关联 workflow run（"甩节点 session 给我"）
**已现成**：`DefaultWorkflowAgentInvoker` 每个 `agent()` 调 `createSubSession(anchor, entityId, runId)` + `attachStepSubAgentSession(stepRunId, subId)`。所以 run → anchor session；每叶子 step → step_output.subSessionId。设计只需 read API（`getRunDetail`）扩展返回每 iteration 的 stepId+subSessionId+semanticDelta，FE 即可"点节点看 session"。纯 read 扩展，不碰核心。

---

## 4. 命门答案 + 退路

**结论**：当前**只能 spawn agent**，要确定性 tool 节点**必须新增 `tool()` host binding**（Phase 1 核心引擎改动，范围小、与现有同构）。

### 4.1 新增 `tool()` binding（首选）
新增 `HostToolCall`（仿 `HostAgent` 但不 spawn agent、不进 LLM）：
```
var res = tool('TriggerAbEval', { targetAgentId:a, surface:'prompt', candidateId:c, evolveRunId:r })
```
- 注册：`HostBindings.register` 加 `define(scope,"tool",new HostToolCall(...))`。
- 在 **workflow 线程同步**执行（机械步骤快/阻塞 read，与 sequential `agent()` 同阻塞模型）。
- 走 `WorkflowToolInvoker`（仿 agent invoker）：维护**白名单 registry**（只 6 个机械 tool + GetCandidateDiff），用 system `SkillContext` 直接 `tool.execute`，**复用 tool 的 execute → service**（"保留 6 tool"决策成立）。落 step（§3.1）。
- **resume 短路**：tool 节点有副作用，resume 时必须像 `HostAgent` 一样从 journal 取缓存短路（否则重复 TriggerAbEval）。
- **安全**：白名单限定；JS 源是受信 classpath（非用户输入），比"orchestrator prompt untrusted surface"更安全。
- **为什么经 tool 不直接调 service**：tool execute 已封装 ownership guard/budget cap/参数解析，复用最省。

### 4.2 退路
- **退路 A（机械步骤叶子过渡）**：P1 先不加 binding，机械步骤暂做 agent 叶子（放开 evolve tool registry）。缺点：违背铁律 + 保留少量 LLM 选工具漂移（但 1-2 轮有界，远比 25 轮 orchestrator 安全）。仅临时退路。
- **退路 B（opt-report 子流程触发）**：B1（推荐）`tool('RunOptReportSubflow')` 内部 `startRun("opt-report",{autoApprove:true})` + 阻塞等完成；B2 workflow→workflow 嵌套需确认 `ConsolidationLock` 不自锁（不同 name 锁不冲突，但要测）→ 列 Q1。

---

## 5. 改动清单

**新增**：`evolve-loop.workflow.js`；`HostToolCall` + `WorkflowToolInvoker` + `DefaultWorkflowToolInvoker` + `WorkflowEvolveToolRegistryFactory`（白名单）；`HostBindings.register` 加 `tool`；`FlywheelRunStepEntity.STEP_KIND_TOOL_CALL`（+ 若有 CHECK 约束的 allow-list migration，database-reviewer 定）；可选只读 `GetCandidateDiff`；候选叶子 system agent `evolve-candidate-gen`（prompt 只讲"读 issue→产候选"，**无循环伪代码**）+ 进 `WorkflowSkillRegistryFactory` 并**只给它**放开 `GenerateCandidate`（Q2 确认不开 recursion）。

**改**：`EvolveController.run` 删 spawn orchestrator session/kickoff/chatAsync → `startRun("evolve-loop",{targetAgentId,maxIter,reportId,autoApprove:true},SYSTEM_USER_ID)`；保留 `hasActiveEvolveRun` 守卫（改查 evolve-loop run）+ reportId 校验。`EvolveReadService.getRunDetail`/`EvolveRunDetailDto` 扩展每 iteration 返 stepId+subSessionId+semanticDelta（纯 read）。

**退役**：`evolve-orchestrator` agent(id=19)+system_prompt；调优 migration V131/V140/V144/V145/V146/V147（**不删历史 migration**，新增一条标记 retired/停 seed，方式 database-reviewer 定 Q3）；`OrchestratorPromptDriftGuardTest`。

**不动**：eval 引擎（沙箱+oracle）、服务层、A/B 算法、数据模型 schema、opt-report 内部、`WorkflowRunnerService` 主体。

---

## 6. Phase 1 MVP 精确范围

**含**：① `tool()` binding + `WorkflowToolInvoker` + 白名单 + `tool_call` step（**不可省的引擎基座**）；② `evolve-loop.workflow.js` 单 surface（prompt only）+ 写死排序 + 写死 keep 阈值 + 确定性轮询 + carry-forward；③ 候选叶子 `evolve-candidate-gen` + 放开 GenerateCandidate；④ 语义 delta 仅候选节点（路径 a + `GetCandidateDiff`）；⑤ `EvolveController.run` 切 `startRun`（灰度开关）；⑥ read API 扩展。

**不做（推后）**：issue 选择用 agent 叶子（P1 写死）；bundle/多面 surface（P1 只 prompt）；产物文件化；opt-report config-aware 检查。

**独立验证**（对照 verification-before-completion）：给已有 completed reportId（避开 opt-report 子流程依赖），`POST .../run?reportId=...&engine=workflow` 跑 1-2 iter。断言：① `FlywheelRun(loop_kind=workflow,name=evolve-loop)` completed；② 每 iter 有 `tool_call`（TriggerAbEval/GetAbResult/RecordIteration）+ `subagent_dispatch`（候选）steps，stepIndex 连续；③ 候选 step 含 `semanticDelta.before/after/diff`；④ `AgentEvolveAbRun` 真出分；⑤ 候选 step 的 subSessionId 能在 `t_session_message` 看到完整对话。**对照基线**：同 reportId 用旧 orchestrator 跑一遍比 iteration 落账一致（行为等价）。

**灰度并存（留退路）**：`EvolveController.run` 加 `?engine=workflow|orchestrator`（默认仍 orchestrator）；orchestrator agent P1 **不退役**，两路并存对比稳定后 Phase 2 再切默认 + 退役。Phase 1 出问题瞬间回退，零停机。

---

## 7. 风险 + 未决问题

**R1（两套 loop_kind 协调）— ✅ spike 已验，风险降为低**：evolve run 是 `loop_kind=evolve`，workflow run 是 `loop_kind=workflow`，`RecordIteration` 校验 `LOOP_KIND_EVOLVE`（`RecordIterationTool:148`）。**采纳方案 (a)**：`startRun` 加 loop_kind 参数（默认 workflow），evolve-loop 传 `"evolve"`。

**spike 取证结论（2026-06-05，代码全量追踪）**：把整个 `workflow/` 包的 `loop_kind=workflow` 用法揪全，只有三处——`WorkflowRunnerService.startRun:193`（建 run）+ `AutoEvolvingOverviewService`（75/77/89/109/110）+ `WorkflowController`（153-160）。**后两者纯展示层；workflow 引擎的 resume/journal-replay/step 记录/完成全靠 `runId`，一处不查 loop_kind**。因此 evolve-loop run 建成 `loop_kind=evolve` 后：① RecordIteration/EvolveReadService:109,144/hasActiveEvolveRun:569/EvolveRunCompletionListener:91 全正常（它们要 evolve）；② workflow 引擎内部正常（runId-based）；③ 该 run 出现在 evolve 仪表盘、不出现在 workflow 仪表盘——**这是正确行为**（它本就是 evolve run）。**唯一小点**：`AutoEvolvingOverviewService` 总览若要显示 evolve-loop run，需 union 两种 loop_kind（纯展示，非 blocker）。方案 (b) workflow 内套子 evolve run 已弃用。

**R2（keep/reject 与 issue 选择）— 已决**：**Phase 1 全写死**。keep 用确定性双标准阈值（种子复用 `GetAbResult.wouldPromote`）；issue 排序用 `severity×recurrence×confidence`。待 loop 稳定（P3）若写死阈值漏判好候选，再升级成有界 agent 叶子。

**R3（`tool()` journal-replay）— 中**：tool 节点有副作用，resume 须从 journal 短路。**Phase 1 可先不支持 humanApprove pause/resume**（autoApprove 直通）把 replay 复杂度推后 → 进一步缩小 P1。

**R4（A/B 阻塞占 workflow 线程）— 低**：GetAbResult 阻塞 90s，多轮长占 `workflowExecutor`。P1 单 run 串行 OK；并发多 agent evolve 需评估池大小（backlog）。

**未决问题**：~~Q1 ConsolidationLock 安全性~~ **✅ spike 已验**：`ConsolidationLock` 是 `ConcurrentHashMap<String,Boolean>` 按 workflow name 分键（per-name），evolve-loop（"evolve-loop"）与 opt-report（"opt-report"）不同键不冲突，嵌套不自锁 → **B1 同步等子流程安全**；Q2 GenerateCandidate 进子 registry 是否松动 recursion guard（不调 A/B 应安全，需 reviewer 确认）；Q3 orchestrator + 调优 migration 退役方式（database-reviewer）；Q4 R1 的 loop_kind 方案——spike 已确认 startRun 加参数最小且引擎 runId-based，**不污染 workflow 通用性**（只是建 run 时可选 kind）。

---

## 8. 硬需求对账

| 硬需求 | 满足点 |
|---|---|
| 全链路可追踪 | §3.1 每节点落 step（新增 `tool_call` kind）；§3.2 语义 delta 写 step_output_json.semanticDelta（候选节点 before/after/diff 主动 emit）；§3.3 sub-session 关联已现成，read API 扩展暴露 |
| MVP 先行+分阶段 | §6 P1 = tool() binding + 单 surface 写死判断 loop + 候选叶子 + 语义 delta；`?engine=workflow` 灰度，orchestrator 不退役留退路；P2/3 加判断叶子/bundle/产物/config-aware |

---

## 9. 诚实边界

最高把握：命门（DSL 无直接调 tool binding —— 直读 `HostBindings`/`HostAgent` 证实）。R1 的 loop_kind 协调、Q1/Q2 的 recursion guard 松动，标了"需 spike/reviewer 确认"而非拍死（依赖 `FlywheelRunService` 校验链 + `ConsolidationLock` 运行时行为，静态读码不足以 100% 担保）。**建议 Phase 1 开工前先用一个 throwaway spike 验掉 R1 和 Q1。**
</content>
