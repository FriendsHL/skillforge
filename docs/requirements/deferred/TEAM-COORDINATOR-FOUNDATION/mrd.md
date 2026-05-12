# TEAM-COORDINATOR-FOUNDATION MRD

---
id: TEAM-COORDINATOR-FOUNDATION
status: mrd
source: user
created: 2026-05-12
updated: 2026-05-12
---

## 用户诉求

> "我创建 teamCreate 然后给了 agentlist 然后创建了这么多的 agent，其实就是想在 main agent 在执行复杂任务的时候直接派发多个 agent 就行了，需要调研多个方向则派出多个 research agent，根据调研开发有 design agent 和 code agent，对于执行结果可以用 session agent 或者再创建个 reviewer agent。问题是我们如何管理这些子 agent 来提高我们的工作效率。"
>
> 用户进一步指示："main agent 应该改名叫 Coordinator 了" / "我们现在好像没有 reviewer agent，我们现在有一个 session 分析的 agent" / "先列下相关需求，然后看后续应该怎么搞。先落个需求包"

## 背景

### 当前 SkillForge multi-agent 能力实况（2026-05-12 调研）

**已有**：
- `t_agent` seed：Main Assistant (V27) / Design Agent (V19) / Code Agent (V26) / memory-curator (V69, SYSTEM dogfood)
- `CollabRunEntity` + `CollabRunService.spawnMember` 派生子 agent，限 `maxDepth=2` / `maxTotalAgents=20` / `MAX_CHILDREN_PER_PARENT=5`
- `TeamCreate / TeamSend / TeamKill / TeamList` 4 个 agent tool
- `AgentRoster` (handle → sessionId 映射)
- `ChatEventBroadcaster` 现有事件：`collabMemberSpawned` / `collabMemberFinished` / `collabRunStatus`
- `SubAgentRunSweeper` 兜底：child idle 30s / no-child 10m / stuck 2h warning
- `EvalAnalysisController` + `AnalyzeCaseModal`：用户挑任意 agent 当"分析师"分析 trace / case / task —— 此为 "session 分析 agent" 模式（不是固定 seed 的 agent，是功能模式）

**没有**：
- ❌ 子 agent 执行中的进度可见性（只在子 agent 自己 chat 里有 message，外部看不到 iter / current tool / tokens）
- ❌ Dashboard 协作看板（collab run 树状视图）
- ❌ 单独的 reviewer agent（**用户明确本期不要**）

### 痛点场景

Coordinator 派 3 个 research-agent 调研 JWT / RBAC / 前端方案，再派 design / code，每个跑 2-5 分钟：

1. **用户视角**：只看到 Coordinator 说"我派了 3 个 research"，然后等到全部回来才有 message。中间黑箱
2. **Coordinator 视角**：阻塞等 [TeamResult] 消息，对子 agent 进度无感知。某个子 agent 卡了要等 SubAgentRunSweeper 30s/10m/2h 兜底才知道
3. **调试视角**：跑挂之后回看哪段卡了 / 哪个子 agent 在干啥，缺数据

### 与 Anthropic Managed Agents 对照

调研 anthropic-sdk-python beta managed-agents（2026-04-01 beta，仍未 GA）发现：
- 它的 **coordinator pattern**（1 层派发 + roster + thread events）跟 SkillForge 现有 `CollabRunService` 形态接近
- 它进度可见性靠 `thread_status_running|idle|terminated` / `span.outcome_evaluation_*` **平台自动出的事件**，**不是让 LLM 调 TeamProgress 工具**
- 它 outcome rubric loop 是 SkillForge `pipeline.md` 的等价物
- 它 retry / outputSchema 都**没有**（行业共识：retry on agent SSE delta 会重复）
- 它 dependsOn 隐式（coordinator 顺序 spawn）

**借鉴**：自动进度事件 + 看板。
**不借鉴**：retry / outputSchema / 声明式拓扑 YAML。

参考链接见 [tech-design.md §架构](tech-design.md)。

## 期望结果

完成后用户应能：

1. 打开 Coordinator 派 collab run 的页面，看到树状看板：Coordinator + 各子 agent，每个子 agent 当前 iter / 正在用的 tool / 已消耗 tokens，实时刷新
2. 子 agent 卡 30 秒以上有视觉告警（区别于"正常运行中"）
3. 任何 agent 在 chat 提到 "Main Assistant" 改为 "Coordinator"，不影响既有 sessionId / agentId 引用

## 约束

- 用户明确**本期不要 reviewer agent** —— session 分析 agent 模式已经覆盖事后审视场景，等 P1 (REQ-7 COLLAB-RUN-ANALYZER) 再做
- 用户明确**不做** retry / outputSchema / TeamPlan YAML / AgentCheckpoint —— 见 [前置讨论结论](../../../../../skillforge-server/docs/manager-agent-enhancement.md) 中评判
- Rename 必须**保证向后兼容**：现有 sessionId / agentId 不变 / 历史 chat 截图里 "Main Assistant" 字样不要求追溯（仅新会话开始用 Coordinator 名）

## 未决问题（PRD ready 前澄清，进入 design 阶段时全部 ratify）

- [x] **D1 进度事件粒度**：每 loop iter / 每 tool start+end / 两者 → ratify 见 [tech-design.md D1](tech-design.md)
- [x] **D2 进度事件载荷必含字段**：iter / currentTool / tokensUsed（**r1 后移除 runtimeStatus**，progress 在飞 ≡ running）→ tech-design D2
- [x] **D3 rename 策略**：直接 update 现有 row vs 新 agent + deprecated → tech-design D3
- [x] **D4 看板路由**：`/collab-runs/:id` 独立页 vs 现有 `/sessions/:id` 内嵌 → tech-design D4
- [x] **D5 看板数据加载**：一次 fetch + WS 增量 vs 每次重 fetch → tech-design D5
- [x] **D6 进度节流**：完整合同 key + window + 双规则 → tech-design D6
- [x] **D7 leader 在看板里的呈现**：独立节点 vs collab run 元数据 → tech-design D7
- [x] **D8 rename 后兼容性**：FE / 日志 / 文档中既有 "Main Assistant" 字串处理 → tech-design D8
- [x] **r1 BLOCKER**：LoopContext 加 collabRunId/handle、跨线程事务、executor 隔离、FE WS dispatch 4 项已修复 → tech-design 评审记录 + B4/B6/F2 节
