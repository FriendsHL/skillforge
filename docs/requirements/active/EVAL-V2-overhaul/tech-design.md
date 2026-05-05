# EVAL-V2 技术方案（rewritten 2026-05-05）

> 状态：**M2 详细化（drafted），其他 milestone 草稿**。M0+M1+Q123 已交付（详见 [delivery-index](../../../delivery-index.md)），技术方案以代码为准。本文档面向待启 milestone（M2 → M3a → M3b → M3c → ...）。

---

## 0. 已交付（不再展开）

- **M0+M1 MVP**（commit `47b331c`）：DatasetBrowser + ScenarioDetailDrawer + AnalyzeCaseModal + EvalOrchestrator WS push + throttle
- **Q1+Q2+Q3 follow-up**（commit `3554569`）：t_session.source_scenario_id (V48 / **deprecated** by M3c) + ScenarioLoader 三路径合并 + BaseScenarioService + AddEvalScenarioTool + DatasetBrowser tab/中文 chip/+新增

---

## 1. M2 — 多轮对话 case 模型（详细化，下一步实施）

### 1.1 Schema (V49)

```sql
ALTER TABLE t_eval_scenario
  ADD COLUMN conversation_turns TEXT NULL;

COMMENT ON COLUMN t_eval_scenario.conversation_turns IS
  'EVAL-V2 M2: JSON array of {role, content} multi-turn conversation. ' ||
  'NULL = single-turn case (use task / oracleExpected). ' ||
  'role: user|assistant|system|tool. content: turn text (assistant placeholder will be replaced at runtime).';
```

**向后兼容**：单轮 case `conversation_turns IS NULL`，走原 task / oracleExpected 路径。

### 1.2 数据格式

```json
{
  "id": "sc-multi-01",
  "name": "Multi-turn debugging help",
  "task": "Help user debug their failing test (multi-turn)",
  "conversation_turns": [
    { "role": "user", "content": "I'm getting NPE in OrderServiceTest.create. Help debug." },
    { "role": "assistant", "content": "<placeholder>" },
    { "role": "user", "content": "Yes I tried that. Still NPE on line 42." },
    { "role": "assistant", "content": "<placeholder>" },
    { "role": "user", "content": "Now it works! What was the root cause?" }
  ],
  "oracle": {
    "type": "llm_judge",
    "expected": "Agent should: (1) ask for stack trace if not given, (2) identify null field on line 42, (3) explain in final turn that the root cause was missing constructor arg."
  }
}
```

`<placeholder>` 是约定占位符，runtime 由 agent 实际响应替换。

### 1.3 EvalOrchestrator 多轮执行协议

新方法 `runMultiTurn(EvalScenario scenario, Agent agent, EvalContext ctx)`：

```
1. 提取 conversation_turns
2. Validate: 至少 1 个 user turn；assistant turns 必须是 placeholder
3. 创建 child session（origin='eval' M3a 后才有，M2 阶段先不动 origin）
4. for each user turn:
   a. agent.handle(userTurn.content, sessionContext) → final response
   b. replace next assistant placeholder with actual response（in-memory，不持久化）
   c. append to running conversation history
5. 完整 conversation 喂给 judge:
   judgeMultiTurn(conversation, oracle.expected, agent.systemPrompt)
6. 拿 compositeScore + attribution + rationale
```

**关键设计**：
- agent **不感知"这是 multi-turn eval"**，每轮就是普通的 user message → final response
- 占位符替换发生在 in-memory conversation 数组，不写回 EvalScenarioEntity
- session 持久化按现有路径（每轮 user/assistant 写 t_session_message）

### 1.4 Judge 协议变化

`EvalJudgeTool` 新方法 `judgeMultiTurnConversation(...)`，prompt template 加：

```
You are evaluating a multi-turn conversation between a user and an AI agent.

Conversation:
[user] {turn1.user.content}
[assistant] {turn1.actual_response}
[user] {turn2.user.content}
[assistant] {turn2.actual_response}
...

Expected behavior:
{oracle.expected}

Evaluation criteria:
1. Per-turn process: 每个 assistant 回应是否合理 / 信息丰富 / 不重复
2. Overall outcome: 整个对话是否解决了用户的问题 / 满足了 expected behavior

Output JSON:
{
  "compositeScore": 0-100,
  "perTurnScores": [{turnIndex, score, comment}],
  "overallScore": 0-100,
  "attribution": "PROMPT_QUALITY | TOOL_FAILURE | CONTEXT_OVERFLOW | NONE | ...",
  "rationale": "..."
}
```

`compositeScore = weightedAverage(perTurn, overall)`，weight 配置化（默认 30% per-turn / 70% overall）。

### 1.5 SessionScenarioExtractorService 多轮抽取

session 有多个 user message → 抽多轮 case；只 1 个 user message → 抽单轮 case（兼容现有）。

```java
public EvalScenarioEntity extract(SessionEntity session) {
    List<SessionMessageEntity> userMsgs = filter(session.messages, role="user");
    if (userMsgs.size() <= 1) {
        // 现有路径
        return buildSingleTurn(session);
    }
    // 多轮路径
    List<Map<String, String>> turns = new ArrayList<>();
    for (msg : session.messages) {
        if (msg.role in [user, assistant]) {
            turns.add(Map.of("role", msg.role,
                             "content", msg.role.equals("assistant") ? PLACEHOLDER : msg.content));
        }
    }
    EvalScenarioEntity scenario = new EvalScenarioEntity();
    scenario.setConversationTurns(objectMapper.writeValueAsString(turns));
    scenario.setTask(buildSummary(userMsgs));  // 单 user msgs 拼接做摘要
    return scenario;
}
```

### 1.6 前端

- **ScenarioDetailDrawer.tsx**：检测 `conversation_turns` 非 NULL → 渲染多轮对话视图（按 role 分气泡 + assistant placeholder 标灰），单轮走原 task / oracleExpected 视图
- **AddBaseScenarioModal.tsx**：advanced disclosure 加 "Multi-turn" toggle → 开启后显示 turns editor（add/remove turns + role 选择 + content textarea），关闭走单轮 task field
- **TypeScript types**: api/index.ts `BaseScenarioInput` 加 `conversationTurns?: Array<{role, content}>`；`BaseScenarioWriteResult` 同步

### 1.7 兼容性 audit（R5）

`PromptImproverService` / `SkillEvolutionService` 当前都用 EvalScenario 跑评测。M2 实施前 audit：
- 这两条路径调用 EvalOrchestrator.run 时是单轮还是会撞到多轮？
- 若 dataset 含多轮 case，这两个 service 的 prompt builder 会不会出错？
- 实施策略：M2 先让多轮 case 在主 EvalOrchestrator 路径跑通，PromptImprover / SkillEvolution 路径如果 dataset 含多轮 case 时 fallback 单轮处理 + 警告日志（不阻塞 M2 交付）

### 1.8 Acceptance（详见 PRD §10 M2）

7 个验收点，详见 PRD。

### 1.9 Dev brief（启 Mid pipeline 时给 dev）

实施顺序：
1. V49 migration + EvalScenarioEntity 加 conversationTurns 字段
2. 后端 BaseScenarioService.addBaseScenario 接受 conversation_turns；ScenarioLoader 透传
3. 后端 EvalOrchestrator.runMultiTurn 实现 + EvalJudgeTool.judgeMultiTurnConversation
4. 后端 SessionScenarioExtractorService 多轮抽取
5. 前端 types + api 函数
6. 前端 ScenarioDetailDrawer 多轮渲染
7. 前端 AddBaseScenarioModal Multi-turn toggle + turns editor
8. 测试：单轮兼容（NULL conversation_turns 不破坏）+ 多轮 happy path（3-turn 真实场景验 judge 输出非 0）+ 多轮抽取测试

预计 1-2 天 Full（schema + 协议 + 跨 BE/FE，触碰 EvalOrchestrator + Judge 是核心 eval 路径）。

---

## 2. M3a — 任务模型重构 + origin 字段（启动前需要更细化）

### 2.1 Schema 总览

V?? migrations（M3a 实施时拍号）：

```sql
-- (a) origin 字段
ALTER TABLE t_session ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'production';
CREATE INDEX idx_session_origin ON t_session (origin) WHERE origin != 'production';

ALTER TABLE t_llm_trace ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'production';
CREATE INDEX idx_trace_origin ON t_llm_trace (origin) WHERE origin != 'production';

-- (b) rename t_eval_run → t_eval_task + 加字段
ALTER TABLE t_eval_run RENAME TO t_eval_task;
ALTER TABLE t_eval_task RENAME COLUMN passed_scenarios TO pass_count;
ALTER TABLE t_eval_task ADD COLUMN attribution_summary TEXT NULL;       -- jsonb
ALTER TABLE t_eval_task ADD COLUMN improvement_suggestion TEXT NULL;
ALTER TABLE t_eval_task ADD COLUMN analysis_session_id VARCHAR(36) NULL;
ALTER TABLE t_eval_task ADD COLUMN dataset_filter TEXT NULL;            -- jsonb
ALTER TABLE t_eval_task ADD COLUMN scenario_count INT;
ALTER TABLE t_eval_task ADD COLUMN fail_count INT DEFAULT 0;
ALTER TABLE t_eval_task ADD COLUMN composite_avg NUMERIC(5,2) NULL;

-- (c) 新表 t_eval_task_item（取代 scenarioResults jsonb）
CREATE TABLE t_eval_task_item (...);

-- (d) 数据迁移：把每个 t_eval_task.scenario_results jsonb 解析为 N 个 t_eval_task_item 行
-- 写一个迁移脚本，one-shot 跑
```

### 2.2 origin 字段在哪 spawn / 改 / 过滤（关键）

**spawn 时设值**：
- `EvalOrchestrator.runScenario` 创建 child session 时 `child.setOrigin("eval")`
- `SubAgentRegistry.attachChildSession` / `CollabRunService.spawnMember` 复制父 origin
- `CompactionService.createBranchFromCheckpoint` 也复制父 origin（branch 是新对话不是 spawn，但属于父的延伸）

**5 处过滤**：
1. `SessionService.list*` 默认 `origin='production'`
2. `TraceController.listTraces` 加 origin 过滤参数（默认 production）
3. `DashboardController.getDailyUsage` / `getUsageByModel` / `getUsageByAgent` SQL 加 `WHERE origin='production'`（cost 不算 eval）
4. `CompactionService.shouldCompact` check `origin='eval'` 跳过
5. `SubAgentStartupRecovery` / `PendingConfirmationStartupRecovery` check `origin='eval'` 跳过 recover

### 2.3 t_eval_task_item 表结构

```sql
CREATE TABLE t_eval_task_item (
    id              BIGSERIAL PRIMARY KEY,
    task_id         VARCHAR(36) NOT NULL,
    scenario_id     VARCHAR(64) NOT NULL,
    scenario_source VARCHAR(16),                -- 'classpath' | 'home' | 'db'
    session_id      VARCHAR(36),                -- t_session FK（执行 chat session）
    root_trace_id   VARCHAR(36),                -- OBS-4 root trace
    composite_score NUMERIC(5,2),
    status          VARCHAR(16) NOT NULL,       -- 'pass' | 'fail' | 'timeout' | 'error'
    loop_count      INT,
    tool_call_count INT,
    latency_ms      BIGINT,
    attribution     VARCHAR(64),
    judge_rationale TEXT,
    agent_final_output TEXT,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (task_id) REFERENCES t_eval_task(id) ON DELETE CASCADE
);
CREATE INDEX idx_task_item_task ON t_eval_task_item(task_id);
CREATE INDEX idx_task_item_scenario ON t_eval_task_item(scenario_id);
CREATE INDEX idx_task_item_session ON t_eval_task_item(session_id);
```

### 2.4 EvalOrchestrator 写双表（替代 scenarioResults jsonb）

每个 case 跑完：
```java
EvalTaskItem item = new EvalTaskItem();
item.setTaskId(taskId);
item.setScenarioId(scenario.getId());
item.setScenarioSource(scenario.getSource());
item.setSessionId(childSession.getId());
item.setRootTraceId(rootTraceId);
item.setCompositeScore(judgeOutput.getCompositeScore());
item.setStatus(runResult.getStatus());
item.setLoopCount(runResult.getLoopCount());
item.setToolCallCount(runResult.getToolCallCount());
item.setLatencyMs(runResult.getExecutionTimeMs());
item.setAttribution(judgeOutput.getAttribution().name());
item.setJudgeRationale(judgeOutput.getRationale());
item.setAgentFinalOutput(runResult.getFinalOutput());
item.setStartedAt(start);
item.setCompletedAt(end);
evalTaskItemRepository.save(item);

// 不再 build scenarioResults map 塞 jsonb（旧路径仍 dual-write 一段时间，过渡期 backward compat）
```

（更多 M3a 细节：rename 期间 backward-compat 视图、前端 caller 切换、Tool RunEvalTask 实现等，M3a 启动前再细化）

---

## 3. M3b — Dataset UI 完整化（启动前简单细化）

### 3.1 ScenarioDetailDrawer 需要展示的字段

按 EvalScenario / EvalScenarioEntity 字段：

| 字段 | 已显示 | 待加 |
|---|---|---|
| name | ✓ | |
| description | | + |
| task | ✓ | |
| oracle.type | | + (chip) |
| oracle.expected | | + (textarea collapsible) |
| setup.files | | + (file list with content preview, collapsible) |
| toolsHint | | + (chip array) |
| tags | | + (chip array) |
| category | ✓ chip | |
| split | | + chip |
| maxLoops | | + |
| performanceThresholdMs | | + |
| extractionRationale | ✓ | |
| recent runs trend | ✓ | |
| analysis sessions | ✓ | |
| source label | ✓ | |

### 3.2 DatasetBrowser 卡片摘要

加 description 一行 / oracle.type chip / tags 前 3 个 chip。

预计 0.5 天 Mid，纯前端改动。

---

## 4. M3c — 归因闭环（启动前简单细化）

### 4.1 Schema (V??)

```sql
CREATE TABLE t_eval_analysis_session (
    id                BIGSERIAL PRIMARY KEY,
    session_id        VARCHAR(36) NOT NULL,         -- chat session id
    task_id           VARCHAR(36) NULL,             -- 哪次 task
    task_item_id      BIGINT NULL,                  -- 哪个 case
    scenario_id       VARCHAR(64) NULL,             -- 哪个 scenario（不绑 task）
    analysis_type     VARCHAR(32) NOT NULL,         -- 'scenario_history' | 'run_case' | 'run_overall'
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (session_id) REFERENCES t_session(id),
    FOREIGN KEY (task_id) REFERENCES t_eval_task(id) ON DELETE SET NULL,
    FOREIGN KEY (task_item_id) REFERENCES t_eval_task_item(id) ON DELETE SET NULL
);
CREATE INDEX idx_eval_analysis_task ON t_eval_analysis_session(task_id);
CREATE INDEX idx_eval_analysis_item ON t_eval_analysis_session(task_item_id);
CREATE INDEX idx_eval_analysis_scenario ON t_eval_analysis_session(scenario_id);
```

### 4.2 三种 Analyze 入口

| 入口 | analysis_type | task_id | task_item_id | scenario_id |
|---|---|---|---|---|
| ScenarioDetailDrawer "分析这个 case 历史" | scenario_history | NULL | NULL | ✓ |
| TaskDetail per-item "分析这个 case 这次跑" | run_case | ✓ | ✓ | ✓ |
| TaskDetail header "分析整 task" | run_overall | ✓ | NULL | NULL |

### 4.3 AnalysisAgent 协议

`POST /eval/tasks/{id}/analyze` 创建 analysis session，初始消息根据 analysis_type 自动构建（含 task data / item trace / scenario history 上下文）；agent 跑完后 RPC 回写 `t_eval_task.attribution_summary` / `improvement_suggestion`（或 by AnalyzeEvalTask Tool 主动写）。

### 4.4 V48 列处理

`t_session.source_scenario_id` 不再被新代码写入；旧数据保留；endpoint `/eval/scenarios/{id}/analysis-sessions` 在 M3c 改为查 `t_eval_analysis_session` 而非 `t_session.source_scenario_id`，URL 不变。

预计 2 天 Mid。

---

## 5. M3d-3g, M4-6 草稿

### 5.1 M3d — Compare runs side-by-side

API: `POST /eval/tasks/compare?ids=a,b` 返回 dataset 交集 + 每 case 跨 task 的 score / status / attribution / outputDiff（如有 final output 对比）。前端 `CompareView.tsx` 表格化。

### 5.2 M3e — Case → trace 跳转

t_eval_task_item.root_trace_id 已有；前端 TaskItem detail 加 "View trace" 按钮 → 跳 `/traces/{rootTraceId}`（OBS-4 trace 详情面板）。

### 5.3 M3f — Annotation queue

新表 `t_eval_annotation`；FE `AnnotationQueue.tsx` 列出待标注 items；标注 → 修 expected_output（versioning，不直接覆盖）→ 触发 re-judge。

### 5.4 M3g — Dataset versioning

`EvalScenarioEntity` 加 `version INT` + `parent_scenario_id VARCHAR(64)`，类似 SkillEntity evolution。FE 加 version selector + version history drawer。

### 5.5 M4 — Score 多维度

`t_eval_task_item` 已有 latency_ms；加 `cost_usd` + `custom_metrics jsonb`。FE 加多 metric 切换。

### 5.6 M5 — Trace 数据回流 dataset

`POST /eval/scenarios/from-trace` body `{rootTraceId, scenarioId?, name?}`：从 trace 抓 input/output → 创建 EvalScenarioEntity（agentId 从 trace 反查）。FE 在 trace 详情面板加按钮。

### 5.7 M6 — Apply suggestion 接 PromptImprover

`POST /eval/tasks/{id}/apply-improvement` 调 PromptImproverService.improvePrompt(agentId, improvementSuggestion)。新 PromptAbRun 创建 + UI 跳到 PromptImprover 页面继续流程。

---

## 6. 待 ratify 决策（详见 PRD §8）

D1-D14 全部已 ratify by user 2026-05-05。M2 实施时无需再问。

---

## 7. 不在范围（详见 PRD §9）

- Online evaluation 自动 sample / Built-in Metrics 库 / Custom Metrics / Test Suites / CI 集成 / API 上传外部 dataset / 多用户 dataset 共享 / P14 接入 / 多 provider 价格区分

---

## 8. 风险（详见 PRD §11）

R1（M2 multi-turn judge prompt）/ R2（M3a rename t_eval_run）/ R3（M3a origin 5 处过滤）/ R4（M3c AnalysisAgent 默认配置）/ R5（PromptImprover / SkillEvolution 兼容性）。
