# EVAL-V2 PRD — 评测系统改造（rewritten 2026-05-05）

> **重写说明**：M0+M1 MVP 已交付（commit `47b331c`），Q1+Q2+Q3 follow-up 也已交付（commit `3554569`）。本版 PRD 基于用户 2026-05-05 试用反馈和评测闭环设计讨论**全面重写**：把分散的 M0-M3 milestone 重组为完整闭环（评测集 → 任务 → 执行 → 评价 → 归因 → 迭代建议），新增任务实体 + 归因关联 + origin 字段。原 v1 PRD（M0-M3 定义）已被覆盖；M0+M1+Q123 实际交付内容以 [delivery-index](../../../delivery-index.md) 为准。

---

## 1. 目标

把 SkillForge eval 系统从"基础堪用"提升到"完整闭环可迭代"，覆盖 **6 个用户原始痛点**：

1. 看到完整测试集（系统 / session 提取 / Tool 新增 三源）
2. 单/多轮对话评测协议清晰
3. 跑评测时实时进度
4. 评测结果显式化（评价 + 归因双轨）
5. 整体 UI 对标 langfuse / opik / coze 罗盘
6. **闭环完整**：评测集 → 任务 → 执行 → 评价 → 归因 → 迭代建议 → 接 PromptImprover

**不做**：替代 OBS-1/2/4 trace observability（trace 是引用关系不是替代）；P14 tau-bench / SWE-bench 接入；多用户 dataset 共享（依赖 P12-PRE auth model 升级）。

---

## 2. 顶层架构

```
┌──────────────────────────────────────────────────────────────────┐
│  EVAL-V2 完整闭环                                                 │
└──────────────────────────────────────────────────────────────────┘

   1. 评测集（统一抽象）           2. 任务调度          3. 执行（复用）
   ┌──────────────────┐           ┌──────────────┐    ┌─────────────┐
   │ • 系统内置 (cp)   │           │ t_eval_task  │    │ t_session   │
   │ • session 提取    │ ────→     │ + items      │ ─→ │ t_llm_trace │ origin='eval'
   │ • Tool 新增       │  选 agent │              │    │ t_llm_span  │
   │ 单/多轮 case      │           │              │    │ (OBS-1/2/4) │
   └──────────────────┘           └──────┬───────┘    └─────────────┘
                                          │
                          4. 评价（机械 LLM judge）
                          ┌───────────────▼─────────────────┐
                          │ t_eval_task_item                │
                          │ score / status / loops / tools  │
                          │ latency / attribution           │
                          └───────────────┬─────────────────┘
                                          │
                          5. 归因（推理 analysis agent）
                          ┌───────────────▼─────────────────┐
                          │ task.attribution_summary        │
                          │ task.improvement_suggestion     │
                          │ t_eval_analysis_session 关联    │
                          └───────────────┬─────────────────┘
                                          │
                          6. 迭代建议 → 7. 接 PromptImprover (V3, M6)
```

**关键设计原则**：
- 评测集统一抽象（三源），但**多源持久化**（classpath JSON / home dir JSON / DB），M3g 时考虑统一
- **复用 trace**：t_session + t_llm_trace + t_llm_span 不重建轨迹，仅加 `origin` 字段区分（D10）
- **任务 vs 评价 vs 归因 三层**：任务表存调度元数据，item 表存评价指标，task 字段存归因汇总
- **评价（机械）和归因（推理）拆分**：LLM Judge 算分，AnalysisAgent 推理建议；不同 agent / 不同时机 / 不同输出粒度

---

## 3. Milestone 拆分（7 个，3 阶段）

### Phase 1 — 闭环基础（P0，必做）

| ID | 内容 | 估时 | 风险 |
|---|---|---|---|
| **M2** | 多轮对话 case 模型 | 1-2 天 Full | 跨 EvalOrchestrator + Judge + Extractor |
| **M3a** | 任务模型重构（task / item 双表 + rename t_eval_run + origin 字段） | 4-5 天 Full | 红灯（schema migration + 替代 jsonb scenarioResults + 改 EvalOrchestrator + 改前端 + 改 OBS dashboard / SubAgent / Compaction / Recovery 5 处 origin 过滤） |
| **M3b** | Dataset UI 完整化（含 Gap A：drawer 显示完整字段） | 0.5 天 Mid | 仅前端 |
| **M3c** | 归因闭环（含 Gap B：Analyze 重构 + t_eval_analysis_session 关联表） | 2 天 Mid | 关联表 + 三种 Analyze 入口 + AnalysisAgent 协议 |

**Phase 1 总计**：~7-9 天 Full。

### Phase 2 — 体验对标（P1）

| ID | 内容 | 估时 |
|---|---|---|
| **M3d** | Compare runs side-by-side | 1-2 天 Mid |
| **M3e** | Case → trace 跳转（M3a 后顺手做） | 0.5 天 Mid |
| **M3f** | Annotation queue（人工标注 / 修 expected） | 2-3 天 Full |
| **M3g** | Dataset versioning（version + parent_scenario_id） | 1-2 天 Mid |

**Phase 2 总计**：~5-8 天。

### Phase 3 — 高级能力（P2，按需）

| ID | 内容 | 估时 |
|---|---|---|
| **M4** | Score 多维度（latency / cost / custom metrics） | 1-2 天 Mid |
| **M5** | Trace 数据回流 dataset（trace 详情 "Add to dataset"） | 0.5 天 Mid |
| **M6** | Apply suggestion 接 PromptImprover（一键 prompt 改进流） | 1-2 天 Mid |

**Phase 3 总计**：~3-6 天，按需启动。

---

## 4. 数据模型（5 新表 + 3 现有扩展）

| 表 | 状态 | Milestone | 关键字段 |
|---|---|---|---|
| `t_session` | **现有扩展** | M3a | + `origin VARCHAR(16) NOT NULL DEFAULT 'production'`（D10）+ partial index `WHERE origin != 'production'` |
| `t_llm_trace` | **现有扩展** | M3a | + `origin VARCHAR(16) NOT NULL DEFAULT 'production'`（D10）+ partial index |
| `t_llm_span` | **现有** | — | 不加 origin（按 trace_id JOIN 推导） |
| `t_eval_scenario` | **现有扩展** | M2 / M3g | + `conversation_turns TEXT NULL`（M2）+ `version INT` + `parent_scenario_id`（M3g） |
| `t_eval_task` | **新** | M3a | id / agent_id / dataset_filter / scenario_count / status / pass_count / fail_count / composite_avg / attribution_summary（jsonb）/ improvement_suggestion / analysis_session_id / created_by / created_at / started_at / completed_at |
| `t_eval_task_item` | **新** | M3a | id / task_id FK / scenario_id / scenario_source / session_id / root_trace_id / composite_score / status / loop_count / tool_call_count / latency_ms / attribution / judge_rationale / agent_final_output / started_at / completed_at |
| `t_eval_analysis_session` | **新** | M3c | id / session_id (FK to t_session) / task_id NULL / task_item_id NULL / scenario_id NULL / analysis_type (scenario_history / run_case / run_overall) / created_at |
| `t_eval_annotation` | **新** | M3f | id / task_item_id FK / annotator_id / original_score / corrected_score / corrected_expected / status (pending / applied) / created_at |
| `t_eval_run` | **rename / drop** | M3a | rename → `t_eval_task` + 数据迁移；保留 backward-compat 视图直到所有 caller 迁移完成 |
| `t_session.source_scenario_id` | **deprecated** | M3c | V48 加的列被 t_eval_analysis_session 替代，列保留为 dead schema 不删（避免 PG dirty migration），M3c 完成后停止写入 |

---

## 5. API 层

| 新 endpoint | Milestone | 用途 |
|---|---|---|
| `POST /api/eval/tasks` | M3a | 创建评测任务（替代 POST /eval/runs） |
| `GET /api/eval/tasks?agentId&status` | M3a | 任务列表 |
| `GET /api/eval/tasks/{id}` | M3a | 任务详情 |
| `GET /api/eval/tasks/{id}/items` | M3a | 任务 items（per-case 结果，替代 jsonb scenarioResults） |
| `GET /api/eval/tasks/{id}/items/{itemId}` | M3a | 单 item 详情（含 trace 跳转） |
| `POST /api/eval/tasks/{id}/analyze` | M3c | 整 task 归因（创建 analysis session） |
| `POST /api/eval/tasks/{id}/items/{itemId}/analyze` | M3c | per-item 归因 |
| `GET /api/eval/tasks/{id}/analysis-sessions` | M3c | 反查 task 的所有 analysis sessions |
| `POST /api/eval/tasks/compare?ids=a,b` | M3d | 跨 task 对比 |
| `POST /api/eval/annotations` | M3f | 人工标注 |
| `POST /api/eval/scenarios/{id}/version` | M3g | 创建 scenario 新版本 |
| `POST /api/eval/scenarios/from-trace` | M5 | trace → dataset 一键加 |
| `POST /api/eval/tasks/{id}/apply-improvement` | M6 | 接 PromptImprover |

**已交付**（不重建）：`GET /eval/scenarios?agentId=X`、`GET /eval/scenarios/{id}/recent-runs`、`GET /eval/scenarios/base`、`POST /eval/scenarios/base`、`GET /eval/scenarios/{id}/analysis-sessions?userId=X`（V48 时代的 analysis-sessions 接口在 M3c 改用 t_eval_analysis_session 后端实现，URL 不变保持兼容）。

---

## 6. Tool 层

| Tool | 状态 | Milestone | 用途 |
|---|---|---|---|
| `AddEvalScenario` | **已交付** | (delivered Q3) | agent 通过 tool 添加 base scenario |
| `RunEvalTask` | **新** | M3a | agent 启动评测任务（input: agentId + datasetFilter） |
| `AnalyzeEvalTask` | **新** | M3c | agent 自助分析任务 + 输出归因 |
| `ApplyImprovement` | **新** | M6 | agent 应用改进建议（接 PromptImprover） |

---

## 7. UI 层

| Milestone | 改动 |
|---|---|
| **M3b** | DatasetBrowser 卡片显示完整字段；ScenarioDetailDrawer 加 description / oracle.type / oracle.expected / setup.files (collapsible) / toolsHint / tags / category / split / maxLoops / performanceThresholdMs |
| **M3a** | 新 `pages/EvalTasks.tsx`（替代 Eval.tsx Runs tab）+ `TaskDetail.tsx` + per-item card 重构（含 trace 链接）；旧 `Eval.tsx` 数据来源改 task API；OBS dashboard 加 origin 过滤 toggle |
| **M3c** | EvalTaskDetail 加"整体归因"按钮 + per-item "分析这个 case"；AnalyzeCaseModal 重写为支持 task / item / scenario 三种 context；ScenarioDetailDrawer 反查 analysis sessions（端点不变） |
| **M3d** | 新 `CompareView.tsx` 选 2 task 跨 case 对比 |
| **M3e** | TaskItem 详情加 "View trace" 跳转链接 |
| **M3f** | 新 `AnnotationQueue.tsx` 人工标注界面 |
| **M3g** | DatasetBrowser 加 version 选择 + version history drawer |

---

## 8. 决策点（用户 2026-05-05 ratify）

### D1. 多轮 case 数据模型 ✅ 选 (a) jsonb 列

`EvalScenarioEntity` 加 `conversation_turns TEXT NULL` 字段（jsonb-encoded `[{role, content}, ...]`）。NULL 时退化单轮（向后兼容）。

### D2. Progress streaming 协议 ✅ 选 (a) UserWebSocketHandler

复用现有 user-level WS push channel。

### D3. Dataset 实体设计 ✅ 选 (a) 沿用 EvalScenarioEntity（M0-M2 阶段）

M0 不引入新 entity；M2 内仅扩字段；M3g 真做 dataset versioning 时考虑独立 entity。

### D4. M0 stretch "分析 agent" 入口 ✅ 选 (a) 纳入 M0（已交付）

### D5. M3 是否引入 langfuse-style RunItem 实体 ✅ 选 (a) 是

M3a 实现 `t_eval_task_item` 替代 `t_eval_run.scenarioResults` jsonb（对标 langfuse `DatasetRunItem`）。

### D6. t_eval_run 处理 ✅ 选 (a) rename → t_eval_task

M3a rename + 加字段 + 数据迁移（量小，写迁移脚本一次性跑）。保留 backward-compat 视图过渡期。

### D7. 多轮 conversation 存哪 ✅ 选 (a) t_session.messages 引用

t_eval_task_item 仅存 session_id 指针，复用 OBS-1/2/4。conversation_turns 字段在 t_eval_scenario（输入 spec），实际跑出来的对话用 session messages。

### D8. 评价 vs 归因表合并 ✅ 选 (a) 拆开

评价进 t_eval_task_item（每个 case 跑完即时算 LLM judge）；归因汇总进 t_eval_task 字段（task 全跑完后由 AnalysisAgent 推理填）。

### D9. Analysis 关联设计 ✅ 选 (a) 独立表 t_eval_analysis_session

V48 加的 `t_session.source_scenario_id` 列保留 dead schema 不删，M3c 完成后停止写入；新关联表支持 (task / item / scenario) 三种 analysis_type。

### D10. session/trace origin 字段（新） ✅ 选 (a) t_session + t_llm_trace 加 origin

`origin VARCHAR(16) NOT NULL DEFAULT 'production'`，partial index `WHERE origin != 'production'`。值 = `'production'` / `'eval'`（未来可加 `'replay'` / `'shadow'`）。t_llm_span 不加（按 trace_id JOIN）。spawn child 时复制父 origin（SubAgent / TeamCreate / Compaction branch 路径都改）。

5 处现有路径加 origin 过滤：`SessionService.list` / OBS `/api/traces` / `getDailyUsage` / `CompactionService` / Startup recovery (SubAgent / PendingConfirmation)。

### D11. t_eval_task 状态机 ✅

`pending → running → completed / failed / cancelled` 五状态。

### D12. task 跑动同步 vs 异步 ✅ 选 异步

保持现 EvalOrchestrator 异步模式 + WS push event（已搭好）。

### D13. dataset_filter 表达 ✅ 选 jsonb

`{"ids": [...], "category": "x", "split": "held_out", "tags": ["x"]}` 灵活组合。

### D14. 多用户 dataset 共享 ⏳ 暂缓

不在 EVAL-V2 范围，依赖 P12-PRE auth model 升级。

---

## 9. Out of Scope

- **Online evaluation 自动 sample**（在 production trace 上 spot-check）—— V3，trace 量稳定后
- **Built-in Metrics 库**（Hallucination / AnswerRelevance 等开箱 scorer）—— V2 之后，依赖具体业务 metric
- **Custom Metrics 用户自定义 evaluator** —— V2 之后
- **Test Suites pass/fail 断言**（Opik 独有） —— V2 之后
- **CI 触发 / 定时跑** —— V3，依赖 P12 定时任务 MVP
- **API 上传外部 dataset** —— V3+
- **P14 tau-bench / SWE-bench 接入** —— 暂缓表保留
- **多用户 dataset 共享 / 权限** —— D14，依赖 P12-PRE
- **多 provider 价格区分** —— ModelUsage 页 follow-up

---

## 10. 验收点（按 milestone）

### M2
- [ ] EvalScenarioEntity 加 conversation_turns 字段（V?? migration 跑过）
- [ ] 多轮 case judge 跑通：3-turn 对话 score 不为 0 / attribution 非 NONE / rationale 引用具体 turn
- [ ] 单轮 case 仍能跑（NULL conversation_turns 走原路径）
- [ ] scenario draft 从多轮 session 提取出多轮 case
- [ ] 前端 ScenarioDetailDrawer 显示 conversation_turns（多轮 case 时）
- [ ] 前端 AddBaseScenarioModal 支持新增多轮 case（advanced disclosure）

### M3a
- [ ] V?? migration: t_session/t_llm_trace 加 origin column + 索引
- [ ] V?? migration: t_eval_run rename → t_eval_task + 加新字段
- [ ] V?? migration: 建 t_eval_task_item 表 + 数据迁移（jsonb scenarioResults → 行级 items）
- [ ] EvalOrchestrator 跑动时为 child session 设 origin='eval'，写 t_eval_task_item 而非 jsonb
- [ ] SubAgentRegistry / CollabRunService / CompactionService spawn child 时复制父 origin
- [ ] SessionService.list / OBS /api/traces / getDailyUsage 加 origin 过滤（默认 production）
- [ ] SubAgentStartupRecovery / PendingConfirmationStartupRecovery 跳过 origin='eval'
- [ ] RunEvalTask Tool 注册 + agent 能 dispatch 跑评测
- [ ] 前端 pages/EvalTasks.tsx 替代旧 Runs tab 数据源；TaskDetail 完整渲染 task + items

### M3b
- [ ] ScenarioDetailDrawer 渲染所有 EvalScenario 字段（description / oracle / setup / toolsHint / tags 等）
- [ ] DatasetBrowser 卡片显示更完整摘要（不只是 task 一行）

### M3c
- [ ] V?? migration: 建 t_eval_analysis_session 表
- [ ] 三种 Analyze 入口：task / item / scenario 都能创建 analysis session
- [ ] AnalysisAgent 跑完后写回 t_eval_task.attribution_summary + improvement_suggestion
- [ ] AnalyzeEvalTask Tool 注册
- [ ] ScenarioDetailDrawer "Analysis sessions" 段从 t_eval_analysis_session 反查（替代 V48 列查询）
- [ ] 旧 V48 t_session.source_scenario_id 列不再被新代码写入（保留旧数据兼容）

### M3d-3g, M4-6
（详见各 milestone 描述；启实施时再展开 acceptance）

---

## 11. 风险

- **R1（M2）** EvalJudgeTool 处理多轮 conversation 的 prompt 复杂度增加，可能需要专门的 multi-turn judge prompt template；初版可能 hallucinate。M2 实施时跑 3-turn 真实场景验证 judge 输出质量。
- **R2（M3a）** rename t_eval_run → t_eval_task 是触碰 EvalController + EvalOrchestrator + 前端 5+ caller 的大改。strategy: V49 先建新表 + dual-write 过渡 + 数据迁移脚本 + 1 周后 V50 drop 旧表。或者直接一次切换（量小可以）。M3a 实施前再决定。
- **R3（M3a origin 字段）** 5 处 origin 过滤路径每一处都要 audit 不漏（SessionService / OBS / cost / Compaction / Recovery）。M3a 实施时跑 e2e：跑一个 eval task，确认用户 chat 列表 / OBS dashboard / cost 页都不混入 eval 流量。
- **R4（M3c AnalysisAgent）** AnalysisAgent 是另一个 agent，需要预先配置好（system prompt + tools）。M3c 实施时，PRD 要 ratify "默认 AnalysisAgent 是哪个"。
- **R5（PromptImprover / SkillEvolution 兼容性）** M2 多轮 / M3a 任务模型重构后，现有 PromptImproverService.improvePrompt 和 SkillEvolutionService.fork 都用 EvalScenarioEntity 和 t_eval_run，要 audit 这两条路径的兼容性。

---

## 12. 估时与排期

| Phase | Milestones | 估时 | 启动门槛 |
|---|---|---|---|
| **Phase 1** | M2 → M3a → M3b → M3c | 7-9 天 | 立刻可启 M2（D1-D14 已 ratify） |
| **Phase 2** | M3d / M3e / M3f / M3g | 5-8 天 | Phase 1 验收后启 |
| **Phase 3** | M4 / M5 / M6 | 3-6 天 | 按需 |

**当前位置**：Phase 1 / M2 启动前。下一步：dev brief 化 M2 实施细节，启 Mid pipeline。

---

## 13. 用户体验流（Phase 1 完成后）

```
用户打开 Evals 页
 ↓
左侧 Datasets browser（系统 / session 提取 / 用户加 三源）—— 痛点 1 解决
 ↓
点某个 case → drawer 展示完整 task / oracle / setup / toolsHint  —— 痛点 4 部分（M3b 解）
 ↓
点 "Run task" → 选 agent + dataset filter → 后台异步跑（origin='eval'）
 ↓
列表实时 progress: case_running:foo → case_passed:foo (87%)  —— 痛点 3 解决（M1 已交付）
 ↓
跑完 → TaskDetail 页：
  • Overall: pass/fail count, composite_avg, attribution histogram
  • 每个 item card: score + status + loop_count + tool_call + latency + attribution + 折叠 rationale + final output + "View trace"
 ↓
点 "整体归因" or per-item "分析这个 case"
  → 创建 AnalysisAgent chat session（关联 t_eval_analysis_session）
  → AnalysisAgent 看 task 数据 / 单 case trace
  → 输出 attribution_summary + improvement_suggestion 写回 task
 ↓
（M6）用户审建议 → 点 "Apply suggestion" → 调 PromptImproverService 跑 prompt 改进流
 ↓
新版 prompt → 再跑评测 → 看 score 是否提升 → 闭环迭代
```
