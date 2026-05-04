# EVAL-V2 技术方案（草稿）

> 状态：**draft** —— PRD ratify 后细化。当前仅记录关键技术路径供讨论，不作为实施依据。

## 1. M0 — Scenarios browser + Eval 详情显式化

### 1.1 后端

**复用现有**：
- `EvalController.GET /eval/scenarios?agentId={id}` 已有
- `EvalScenarioEntity` 字段已够（name / category / split / task / oracleExpected / status / sourceSessionId / extractionRationale / createdAt）

**新增**：
- 可能需要 `GET /eval/scenarios/{id}/recent-runs?limit=N` 返回该 case 在最近 N 次 run 中的 score 趋势（用于详情 drawer 趋势图）。
- 实现：JOIN `t_eval_scenario` × `t_eval_run.scenario_results` jsonb（按 scenarioId 提取每个 run 该 case 的 score）。

**audit 任务（R1）**：
随机抽 3 个最近 EvalRun，确认 scenarioResults jsonb 真有非零 compositeScore + 非 NONE attribution。如果发现 stub，M0 范围加上 "EvalJudgeTool 实际跑通" 子任务。

### 1.2 前端

**改动**：
- `pages/Eval.tsx` 加顶层 second-level tab `[Runs] [Datasets]`（或独立路由 `/evals/datasets`）
- 新组件 `components/evals/DatasetBrowser.tsx` —— 接 `/eval/scenarios?agentId=...`，用类似 `pages/SkillList.tsx` 的卡片网格 + 搜索 + 过滤
- 新组件 `components/evals/ScenarioDetailDrawer.tsx` —— 复用 `SkillDrawer.tsx` 的 layout 模式（左 80% 内容 + 右 20% 元信息）
- `components/evals/EvalRunDetail` 重构每个 scenario 卡片：score + attribution badge + 折叠 rationale / output

**stretch（M0 末段）**：
- "分析 session" 按钮 → POST `/api/agents/{analysisAgentId}/sessions/{sessionId}/messages` body `{content: "看一下 session {id} 的执行轨迹，给出失败归因 + 优化建议"}`，跳到那个 session chat 页 follow up。前提是用户已配置 "analysis agent"（dropdown 选）。
- 简化：M0 只做 button + dispatch；message 模板硬编码；analysis agent 选择 = source agent 同款 dropdown。

## 2. M1 — Progress streaming

### 2.1 后端

**复用现有**：
- `UserWebSocketHandler` user-level WS push channel
- `EvalOrchestrator` 跑 case 的循环（line ~110-130 附近）

**改动**：
- `EvalOrchestrator` 在每个 case 跑前/跑后调 `userWebSocketHandler.sendToUser(userId, eventJson)`
- 事件 schema：
  ```json
  {"type": "eval_progress", "evalRunId": "...", "event": "case_running",
   "scenarioId": "...", "scenarioName": "...", "passedCount": 3, "totalCount": 12}
  ```
- throttle：if totalCount > 50，per case 不 push，按 5% 增量 push 一次（例 5/12 / 10/12 ...）
  
### 2.2 前端

**改动**：
- 在 Eval 列表行加 progress bar（CSS）
- WS message handler 在 Eval.tsx 监听 `eval_progress` 事件，更新对应 row state
- 注意 WS cleanup（按 frontend.md footgun #2）

### 2.3 cancel & reconnect

- WS 断线后前端 GET `/eval/runs/{id}` 一次拉最新 state
- 不在 MVP 范围：用户主动 cancel eval run（如有需求 V2 加）

## 3. M2 — 多轮 conversation case（V2 草案）

### 3.1 Schema

V48 migration:
```sql
ALTER TABLE t_eval_scenario
  ADD COLUMN conversation_turns TEXT NULL;
COMMENT ON COLUMN t_eval_scenario.conversation_turns IS
  'JSON array of {role, content} turns for multi-turn cases. NULL = single-turn (use task/oracleExpected).';
```

### 3.2 协议

```
Single-turn case:
  task → agent runs → final output → judge {output, oracleExpected, agentTrace}

Multi-turn case:
  conversation_turns = [{role:"user", content:"..."}, {role:"assistant", content:"<placeholder>"}, ...]
  ↓
  逐 turn 喂给 agent：
    - turn 1 (role=user) → agent 回应 → 替换 turn 2 (role=assistant) 的 content
    - turn 3 (role=user) → agent 回应（带前 2 turn 的历史） → ...
    - 直到 conversation 跑完
  ↓
  judge 收到完整 conversation + 原始 expected pattern
    - process score: 每 turn 是否合理（多个 sub-score）
    - outcome score: 最终输出是否符合
    - composite = weighted avg
```

### 3.3 兼容性

- 单轮 case（conversation_turns NULL）走原 path
- 多轮 case judge 必须 LLM judge（无法 rule-based 评估多 turn）
- `SessionScenarioExtractorService` 也要扩展：识别 session 多 turn → 抽多轮 case

## 4. M3 — UI 对标（V2 草案）

### 4.1 Dataset 一等公民

可能新 entity：
```sql
CREATE TABLE t_eval_dataset (
  id BIGSERIAL PRIMARY KEY,
  agent_id VARCHAR(36),
  owner_id BIGINT,
  name VARCHAR(256),
  tags TEXT,  -- jsonb array
  parent_dataset_id BIGINT,  -- versioning
  ...
);
ALTER TABLE t_eval_scenario ADD COLUMN dataset_id BIGINT;
```

或者**沿用 EvalScenarioEntity** 用 `category` + `tags` 字段聚合（更轻），**M3 实施时再决**。

### 4.2 Compare runs

- API: `GET /eval/runs/compare?ids=run1,run2`
- 返回 dataset 交集 + 每 case 跨 run 的 score / attribution / outputDiff（如有 final output 对比）
- 前端 `components/evals/CompareView.tsx` 表格化展示

### 4.3 Annotation queue

- 新 entity `t_eval_annotation`：scenarioId + annotatorId + correction + originalScore + correctedScore + ...
- 修改 expected_output → 触发 re-judge（rerun queue）
- audit log 持久化

### 4.4 Case → trace 链接

- scenarioResult jsonb 加 `traceId` / `rootTraceId`（OBS-4 数据已就位）
- 前端点 case 详情 → 跳 `/traces/{rootTraceId}` 路由（OBS-1/4 trace 详情面板）

## 5. 风险落地策略（PRD §7）

- **R1**（judge stub 风险）：M0 第一步先 audit 实际 EvalRun，如果是 stub 则 M0 范围补 EvalJudgeTool 真跑通（cost：~0.5 天加）
- **R2**（WS 高频推送）：throttle 在 EvalOrchestrator 端，case 多时按 5% 增量 push
- **R3**（M2 跨 service 兼容）：M2 实施前 grep PromptImproverService / SkillEvolutionService 对 EvalScenarioEntity 的所有读写位置，每个加分支或保持单轮兼容
- **R4**（M3 annotation revert）：annotation 不直接覆盖 expected_output，而是新版本 + 显式 promote，留 rollback 路径

## 6. 待 ratify 决策（详见 [PRD §6](prd.md)）

- D1 多轮模型：jsonb vs 新表 → 推荐 jsonb
- D2 progress 协议：WS vs SSE → 推荐 WS
- D3 Dataset 实体：沿用 EvalScenarioEntity vs 新 entity → 推荐 M0 沿用，M3 再决
- D4 M0 stretch "分析 agent" 入口 → 推荐纳入

## 7. 不在草稿范围

- 详细 SQL / API schema（PRD ratify 后细化）
- 各 milestone 的 self-check checklist
- pipeline pipeline trail（启实施时按 SkillForge `pipeline.md` 走 Full）
