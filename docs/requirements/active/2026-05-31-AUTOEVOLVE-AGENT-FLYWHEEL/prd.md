# PRD — AUTOEVOLVE-AGENT-FLYWHEEL

> 状态：prd-draft。承接 [index.md](index.md)。决策记录见文末。

## 1. 目标

1. **平台能力**：让 agent 能**调用**已存 workflow（按名字）+ **现写现跑** workflow（inline JS，不持久化）。
2. **Auto-Evolving 系统**：一套 **agent 驱动**的自动 loop，对一个目标 agent 的生产 session 做：自动评测 → 自动归因 → 自动迭代改 surface（prompt/skill/rule）→ A/B 验证 → 科学 gate 判断保留 → 循环 N 次 → 人末尾定夺采纳。
3. **可观测**：把每轮"改了什么 + 分数 delta + 是否保留"记下来，用**轨迹图**让用户看到"飞轮是否真在提升 agent"。
4. **不重复造轮子**：复用现有评测引擎 / opt-report workflow / improver / promote，新增只在编排层 + tool 包装 + 可观测。

## 2. 非目标

- 灰度 canary（v1 不加；人定夺后直接改配置）。
- 多 surface 同时改 + per-task 交互归因（模块 E 进阶，非 v1）。
- retrofit 现有事件驱动飞轮（**关掉它的 auto-trigger，另建 agent 驱动编排**）。
- 同步 A/B wrapper（agent 驱动用异步 A/B + 轮询，不需要同步入口）。
- 框架自进化（V5）/ AUTORESEARCH（V2 另包）/ workflow 持久化命名（DB 表，本包 inline 不存）。

## 3. 整体流程（用户复述确认版）

```
顶层 orchestrator agent 驱动：
① RunWorkflow('opt-report')
     → opt-report workflow 内部（现有，复用）：
         agent A: 拉目标 agent 7/20 天全部 session
         agent B: batch sub-agent 对每个 session 做指标评估
         agent C: 对所有评估归因 → 产出 report
     → 返 report
② loop（maxIter N 次，如 10/20）：
     GenerateCandidate(issue)   → 改 surface 候选
     TriggerAbEval(candidate)   → 触发异步 A/B（baseline 有则跳过、没有跑一次 + 跑改后 benchmark）
     GetAbResult(abRunId)       → 轮询拿两份分（baseline + candidate）
     <科学 gate>                → 提升达标则保留（gate 可配置，非写死 +Npp）
     RecordIteration(...)       → 落迭代账本
③ humanApprove（末尾）          → 人看轨迹图，定夺是否采纳整套
④ PromoteCandidate(adopted)    → 采纳的真 promote（走现有守卫）
```

## 4. 各模块功能需求（FR）

### 模块 A — Workflow 操作能力
- **FR-A1**：`RunWorkflow(name, args)` tool — 跑 registry 已存 workflow，返 runId。
- **FR-A2**：`RunWorkflow(script, args)` tool — agent 传 inline JS（含 `export const meta`），引擎解析 + L1 sandbox 校验 + 直接跑，**不持久化**，返 runId。
- **FR-A3**：tool 注册进**主 SkillRegistry**，**不进** WorkflowSkillRegistryFactory（子 agent registry）；inline 跑同一 L1 sandbox。
- **FR-A4**：不默认塞给所有 agent，靠 `tool_ids` 指定给 orchestrator / chat agent。

### 模块 B — A/B 封装成 Tool
- **FR-B1**：`TriggerAbEval(surface, candidateId, baselineId?)` tool — 触发现有异步 A/B（复用 `runAbTestAgainst` / `AbEvalPipeline`），返 abRunId。
- **FR-B2**：`GetAbResult(abRunId)` tool — 轮询 abRun 状态，返 {status, baselineScore, candidateScore, delta, perScenario?}。terminal 才返完整分。
- **FR-B3**：`PromoteCandidate(surface, candidateId)` tool — 包装现有 promote service，**走现有守卫**（delta gate / 24h cooldown / paused），返结果。
- **FR-B4**：baseline 缓存——`TriggerAbEval` 若 baseline 已有近期 ab_run 分数则跳过 baseline 重跑（复用 ab_run 已存分数）。

### 模块 C — Auto-Evolving Orchestrator
- **FR-C0**（review 补）：**触发入口**——`POST /api/evolve/agents/{agentId}/run`（或 ScheduledTask）建一个 system session 绑 orchestrator agent + `chatService.chatAsync`,仿现有 `POST /api/flywheel/agents/{agentId}/run-loop`（`FlywheelChainOrchestrator` + `AttributionDispatcherService.dispatchOne` 模式）。原计划漏了"谁启动 orchestrator"。
- **FR-C1**：新建 orchestrator agent（t_agent），tool_ids = [RunWorkflow, GenerateCandidate, TriggerAbEval, GetAbResult, RecordIteration, PromoteCandidate]，system_prompt 写 loop 策略。**顶层运行**（FR-C0 入口启动），非 workflow 子 agent。**仿 `attribution-dispatcher` agent 模式**（已有的 LLM 编排 dispatch loop），非从零设计。**RecordIteration 归到 C**（不放 D），否则 C 首次 e2e 记空。
- **FR-C2**：`GenerateCandidate(surface, issue, targetAgentId)` tool — 包装现有 improver service（SkillDraft/Prompt/BehaviorRule，one-shot LLM 生成候选），返 candidateId。
- **FR-C3**：loop 策略：maxIter（可配，如 10/20）；每轮选下一个 issue / surface；**科学 gate**（delta 阈值 / 多场景 / 显著性，可配，非写死）判断"保留"（= 候选记账，不立即 promote）。
- **FR-C4**：report-gen 复用现有 opt-report workflow（orchestrator 通过 RunWorkflow 调）。
- **FR-C5**：末尾 humanApprove — 人看轨迹定夺采纳；采纳的走 PromoteCandidate。
- **FR-C6**：关掉现有事件驱动 auto-trigger（全局 flag `skillforge.flywheel.auto-trigger-ab-on-candidate-ready=false`）防两套编排对同一 agent 跑。
- **FR-C7**（Module B security review carry-over，HIGH-1）：**per-evolve-run A/B 预算上限**——orchestrator 的 maxIter 在 agent prompt（不可信面），通用 maxLoops=25 兜底太松（25 次真 A/B = 大量 LLM 算力）。evolve-run 实体（复用 FlywheelRun loop_kind=evolve）记一个 A/B 触发计数,**服务端硬上限**（TriggerAbEval 调用时检查 evolve-run 的 A/B 次数 < cap,超则拒）。需 evolve-run 上下文,故归 Module C（Module B 的 TriggerAbEval 单独无 run 上下文做不了）。

### 模块 D — 迭代账本 + 轨迹可观测
- **FR-D1**：迭代账本实体（每轮一行：runId / iteration / surface / changeDesc / candidateId / baselineScore / candidateScore / delta / kept / createdAt）。
- **FR-D2**：`RecordIteration(...)` tool — orchestrator 每轮落账。
- **FR-D3**：轨迹页 — 分数随迭代的折线图（往上爬 / 某轮掉），每点标注"这轮改了什么"。挂 /autoevolving 或 FlywheelObservability。
- **FR-D4**：跨多次整体运行也能看（同一目标 agent 的多次 evolve run 叠加轨迹）。

### 模块 E — AHE 借鉴（进阶）
- **FR-E1**：候选加 `predicted_impact`（预测哪些 task fail→pass / pass→fail）。
- **FR-E2**：per-task A/B 结果对比（prompt 已有 per-scenario；**skill 需补 per-scenario baseline**）。
- **FR-E3**：证伪——实际 flip vs 预测 flip 逐 task 对比，预测失败 → rollback/revise。解 A+B 交互归因。

## 5. 验收标准（AC）

- **AC-A**：orchestrator/chat agent 调 `RunWorkflow('opt-report')` 能跑出 report runId；调 inline script 能现写现跑。子 agent 调不到 RunWorkflow（验证 registry 隔离）。
- **AC-B**：agent 调 `TriggerAbEval` → `GetAbResult` 轮询拿到 baseline+candidate 分；`PromoteCandidate` 走守卫（不达 gate 被拒）。baseline 已有时跳过重跑。
- **AC-C**：orchestrator agent 端到端跑一次：report → N 轮（生成候选→A/B→判断→记账）→ 末尾 humanApprove → 采纳 promote。真实目标 agent 上验证。
- **AC-D**：轨迹页能看到某次 evolve run 的分数随迭代曲线 + 每轮改动标注；多次运行能叠加对比。
- **AC-E**（进阶）：predicted_impact 落候选；prompt surface 的 per-task 证伪能抓出"预测没兑现"的改动。
- **AC-核心**：在一个真实 agent 上跑完整 loop，**轨迹图显示分数确实提升**（验证"是否真提升 agent"）。

## 6. 决策记录

| # | 决策 | 理由 | 时间 |
|---|---|---|---|
| D1 | **编排用 agent 驱动**（顶层 orchestrator agent），非确定性 JS workflow | 用户明确选 agent 驱动；orchestrator 顶层运行避递归；agent 天然处理异步 A/B（轮询）+ 中间错误 reason | 用户 5-31 |
| D2 | **A/B / promote 包成 tool**（不是 host binding，也不是 workflow 内 agent tool）| orchestrator 是顶层 agent（非 workflow 子 agent）→ A/B-as-tool 只 fan-out 2 层（同现有飞轮），无 architect 发现的 3 层递归陷阱 | 用户 5-31 + architect |
| D3 | **A/B 用异步 + 轮询**，不造同步 wrapper | agent 驱动天然 调→轮询→reason；绕过 architect Q1 同步 wrapper 事务风险 | architect |
| D4 | **拆出来 = 拆编排,复用评测引擎** | 用户："别再现有 agent/workflow 来回适配"；但 A/B 算力是硬且能用的部分,包成 tool 复用不重写 | 用户 5-31 |
| D5 | **关旧事件驱动 auto-trigger**（全局 flag），新建 agent 驱动编排,不 retrofit | 两套编排对同一 agent 跑会 race；旧的为事件驱动建,硬塞 agent 驱动一直摩擦 | 用户 5-31 + architect Q6 |
| D6 | **科学 gate**（可配），+2pp 只是举例 | 保留与否要科学方法（delta/显著性/多场景）,不写死 | 用户 5-31 |
| D7 | **maxIter N 次 + 人末尾定夺 + 灰度先不加** | 看能提升到什么程度,人看轨迹采纳,canary v1 不做降复杂度 | 用户 5-31 |
| D8 | **可观测 = 迭代账本 + 轨迹图**（核心） | 这是"看是否真提升"的验证手段; 也把 agent 驱动丢的可见性(DAG)补回来 | 用户 5-31 |
| D9 | **借 AHE predicted_impact + 错峰缓存** | 交互归因用 task 级预测证伪；baseline 缓存/winner 顺延减重跑（per-task carry-forward 不安全→重测,见 tech-design）| 用户 5-31 + AHE 论文 |
| D10 | **保留单 promote/run**（不绕守卫）| 现有 24h cooldown/promoted-today 守卫是生产安全栏,v1 接受一轮最多 promote 1 个；"保留"= 候选记账,真 promote 末尾人定夺走守卫 | architect Q4 |
| D11 | **eventId gap 用 reportId+issueId 桥解，不用 sessionId** | improver 的 `source_event_id` 是 **Long 列**，sessionId 是 String UUID 对不上；而 report 的 topIssues 本就带稳定 issueId + `exampleSessionIds`，复用现成 `OptReportToEventBridge.convertIssueToEvent`（幂等 + sourceReportId/sourceIssueId 审计回链）当场铸 eventId。GenerateCandidate 双锚模式（reportId+issueId 优先 / 直传 eventId 向后兼容），ownerId 默认 SYSTEM_USER_ID，skill 合成 patternId=eventId（patternId 仅审计/命名文本非硬 FK） | 主会话 5-31 + 实测 eventId=125 |
| D12 | **新增 `GetOptReport` 薄读 tool**（RunWorkflow 异步只返 runId，orchestrator 读不到 report） | RunWorkflow('opt-report') 异步 + humanApprove 暂停；orchestrator 要读 topIssues 驱动 loop。复用 `OptReportSummaryParser` + FlywheelRunRepository；`expectedAgentId` **必填且服务端强制**（防跨 agent 泄漏 issue 的 exampleSessionIds，仿 FR-C7 不信 prompt 的硬化思路）。EvolveController 加可选 `reportId` 参数（聚焦 loop 路径：跳过 RunWorkflow 直接 GetOptReport），reportId 正则校验防 prompt 注入 | 主会话 5-31 + security review |
| D13 | **轮询健壮性是已知弱点（待硬化）** | 实测 orchestrator 紧轮询 GetAbResult（~50s 内 14 次，每次夹一个 mimo LLM 回合），而 behavior_rule A/B 要 ~5min → 多分钟等待路径上单次 mimo stream 抖动即杀整个 run（实测 loop 17 挂）。核心链路已验证（GetOptReport→GenerateCandidate 铸 eventId=125→TriggerAbEval→A/B COMPLETED 真分数 baseline 24.4% vs cand 12.2%），但 RecordIteration 未达。关联 D3（异步+轮询）。**backlog**：GetAbResult 阻塞式等待 / orchestrator 容忍单次 LLM 错误重试 | e2e 5-31 |
| BUG-1 | **🐛 baseline 每轮重测 + 不顺延赢家 → 多轮轨迹是噪声** | 实测 e7a99439（agent3 maxIter=3）：同一个 baseline（中途没 promote）每轮被重新评测，因 mimo 非确定性 + 12 小样本，分数狂跳 50%→25%→8.3%。**两个错**：① attribution/evolve A/B 每轮重跑 baseline（PromptImproverService:633 `baselineEvalRunId=null`，"re-evaluating fresh"）→ 浪费一半算力 + baseline 飘成噪声；② 无 winner-carry-forward → 每轮都跟原始 baseline 比。结果 delta 混入 baseline 噪声，**看不出有没有真提升，轨迹失去意义**。**正确设计（用户拍板 = AHE 错峰世代/真爬坡）**：baseline 一个 run 内只测一次；候选**基于当前 best prompt 累积改** + vs best 的**缓存分**（candidate-only 不重测）；候选超 best 过 gate → 该候选 prompt+分**顺延成新 best**；没人赢则不变；真 promote 仍人末尾定夺。5 块改：improver basePromptVersionId / A/B 复用缓存 baseline / orchestrator 跨轮追踪 current-best / RecordIteration 记顺延 / gate=greedy-in-run | 实测 6-1 + 用户拍板，开发中 |
