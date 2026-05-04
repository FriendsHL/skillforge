# EVAL-V2 PRD — 评测系统改造

## 1. 目标

把 SkillForge eval 系统从"链路完整但体验落后"提升到"对标 langfuse/opik 的可信工具"。具体落实为 5 个用户痛点的解法（详 [MRD §1](mrd.md)）+ 数据模型对多轮场景的扩展。

**不做**：替代 trace observability（OBS-1/2/4 已覆盖）；接外部 benchmark（P14 暂缓）；多用户 dataset 共享（依赖 P12-PRE）。

## 2. 范围（M0 → M3）

### M0 — Scenarios browser + Eval 详情显式化（MVP，~1 天，Mid）

**解决**：痛点 1（看不到 case 列表）+ 痛点 4（分数/建议看不到）。

- **Scenarios 独立页面/tab**：路由 `/evals/datasets`（或 Eval 页加 second-level tab "Datasets"），按 agent 维度列出该 agent 所有 `EvalScenarioEntity`。每行显示：name / category / split / oracleType / status / sourceSessionId / createdAt
- **点 case 进入详情**：右侧 drawer 展示 task / oracleExpected / extractionRationale 全文 + 关联的近期 N 次 run 结果（compositeScore 趋势）
- **Eval run 详情面板优化**：当前 Eval.tsx 已渲染 compositeScore + attribution，但位置不显眼。改成卡片视图：每个 scenario 独立卡片，顶部大字号 score + attribution badge + 折叠区展开 judgeRationale + agentFinalOutput
- **"调分析 agent 给意见"入口**（M0 末段，可选 stretch）：在 scenario 卡片加按钮 → 调 SubAgentTool dispatch 已有 agent，task 自动 fill "看一下 session {id} 的执行轨迹，给出失败归因 + 优化建议"

**不动**：EvalScenarioEntity schema；EvalJudgeTool 内部逻辑；progress streaming（M1 做）。

### M1 — Progress streaming（MVP，~0.5 天，Mid）

**解决**：痛点 3（不知道进度）。

- **WS push events**：`eval_run_started` / `case_running:{scenarioId, name}` / `case_passed:{scenarioId, score}` / `case_failed:{scenarioId, attribution}` / `eval_run_completed:{passedCount, failedCount}`
- 前端 Eval 列表行加 progress bar（passedCount / totalCases）+ 当前 case name + simple ETA（按平均 case 时长估算）
- 复用现有 `UserWebSocketHandler` 推送 channel（user-level WS，跟 SUBAGENT 异步派发同模式）

**不动**：eval run 持久化（已经在 EvalRunEntity 写）；判定算法（已有）。

### M2 — 多轮 conversation case 模型（V2，~1-2 天，Full）

**解决**：痛点 2（单轮 vs 多轮）。

- **schema 扩展**：`EvalScenarioEntity` 加 `conversation_turns TEXT (jsonb-encoded)` 字段，结构 `[{role: "user"|"assistant"|"system", content: "..."}]`。`null` 时退化到单轮 `task`/`oracleExpected`（向后兼容）
- **协议层**：Judge 处理多轮时，把 conversation 整体喂给 agent（每个 user turn 后 agent 回应，最后 turn 后 judge 评估整体 outcome + 每 turn process）
- **scenario draft 提取也支持多轮**：session 提取 scenario 时，如果 session 有多个 user message → 抽成多轮 case；否则单轮（兼容现有路径）
- **migration**：V48（或下一个可用版本）`ALTER TABLE t_eval_scenario ADD COLUMN conversation_turns TEXT NULL`

### M3 — UI 对标 langfuse/opik/coze（V2，~3-5 天，Full）

**解决**：痛点 5（整体页面体验）。

基于 [MRD §3](mrd.md) 调研结果，落实**三者共同思路**到 SkillForge：

- **Dataset browser** 一等公民页面（M0 已做 80%；M3 加 group / tag / virtual folder 用 `/` 分组）
- **Compare runs side-by-side**：选 2 个 run → 同 dataset 跨 run case-by-case 对比 score + attribution（对标 langfuse/opik 的 compare）
- **Annotation queue**：评测员标注 case 的 expected_output 改进 / 标注 LLM judge 错误判定（对标 langfuse 4 种评分方法之一）
- **Case → trace 链接**：scenario result 含 traceId，UI 加跳转复用 OBS-1/4 trace 详情面板（对标 langfuse `DatasetRunItem.traceId`）
- **Dataset versioning**：`EvalScenarioEntity` 加 `version` + `parent_scenario_id`，类似 SkillEntity evolution 模型（对标 langfuse/opik 2026-02 都加的能力）
- **Score universal 化**（轻量版）：M3 内允许多 Score 挂同 scenario_result（除 compositeScore 外加 latency / cost / custom metric）；不照搬 langfuse 完整四种类型，仅 NUMERIC + TEXT
- **Trace 数据回流到 dataset**（stretch / M3 末段）：在 trace 详情面板加"Add to dataset"按钮，把现有 trace input/output 一键变 EvalScenarioEntity（对标 Coze 罗盘"trace 自动评测"+ langfuse "Datasets from Production"，但本期是手动触发，自动 sample 留 V3）

**不在 M3**：
- **Online evaluation 自动 sample**（在 production trace 上 spot-check）—— V3，依赖更稳定的 trace 卷
- **Built-in Metrics 库**（Hallucination / AnswerRelevance 等开箱 scorer）—— V2 之后，依赖具体业务 metric 需求
- **Custom Metrics 用户自定义 evaluator**—— V2 之后
- **Test Suites pass/fail 断言**（Opik 独有）—— V2 之后，跟现有 LLM judge composite score 路径不冲突，但增大复杂度
- **CI integration / scheduled runs** —— V3，依赖 P12 定时任务 MVP 先上
- **API 上传外部 dataset** —— V3+
- **DatasetRun + DatasetRunItem 实体重构**（对标 langfuse 数据模型）—— D5 决策点（PRD §6）待 M0/M1 验证后再定

## 3. 验收点

### M0
- [ ] Datasets 浏览器页或 Eval 页 second-level tab 可以列出当前 agent 所有 EvalScenarioEntity
- [ ] 点击 case 显示完整 task + oracleExpected + 近期 N 次 run score 趋势
- [ ] Eval run 详情对每个 scenario 显示 score（百分比）+ attribution badge + judgeRationale 折叠展开 + agentFinalOutput
- [ ] （可选）"分析 session" 按钮调用 SubAgentTool dispatch 已配置 analysis agent

### M1
- [ ] Eval 跑动时前端能实时看到 case-by-case 状态（running / passed / failed）
- [ ] WS 断线 / 重连后 progress 不会出错（reconnect 时按 GET `/eval/runs/{id}` 拉一次最新状态）
- [ ] eval 列表行的 progress bar 跟 passedCount / totalCases 一致

### M2
- [ ] EvalScenarioEntity 加 conversation_turns 字段（Flyway migration 跑过）
- [ ] 多轮 case judge 跑通：3-turn 对话场景 score 不为 0 / attribution 非 NONE / rationale 引用具体 turn
- [ ] 单轮 case 仍能跑（向后兼容）
- [ ] scenario draft 从多轮 session 提取出多轮 case

### M3
- [ ] Datasets 页有 group / tag / version
- [ ] 选 2 run 能 side-by-side compare（同 dataset case-by-case 表）
- [ ] Annotation queue：可以人工标注 / 修改 case expected_output（持久化 + 触发 re-judge）
- [ ] case 详情页有"View trace"跳转链接（带 root_trace_id）

## 4. Out of Scope

- P14 tau-bench / SWE-bench 接入（外部 benchmark，需求未到）
- 多用户 dataset 共享 / 权限（依赖 P12-PRE 多用户决策定型）
- API 上传外部数据集
- CI 触发 / 定时跑（依赖 P12 定时任务 MVP 先上）

## 5. 用户体验流（M0 完成后）

```
用户打开 Evals 页
 ↓
左侧看到 Datasets browser (per agent 维度) — 痛点 1 解决
 ↓
点某个 case → drawer 展示 task / oracleExpected / 近期 score 趋势
 ↓
回到 Evals 页 → 点 "Run eval" → 跳到某次 run 详情
 ↓
(M1) 实时看 progress: case_running:foo → case_passed:foo (87%)  — 痛点 3 解决
 ↓
跑完 → 每 scenario 卡片化展示 score + attribution + rationale + final output  — 痛点 4 解决
 ↓
(M0 stretch) 点 "分析" 按钮 → SubAgent 看 session 给优化建议
```

## 6. 决策点（用户 2026-05-04 ratify 全部按推荐 (a)）

### D1. 多轮 case 数据模型 ✅ 选 (a) jsonb 列

`EvalScenarioEntity` 加 `conversation_turns TEXT NULL` 字段（jsonb-encoded `[{role, content}, ...]`）。NULL 时退化单轮（向后兼容），非 NULL 时按多轮处理。

**理由**：SkillForge 现有 jsonb pattern 已经成熟（MSG-1 payload / SkillEntity scan_report / compaction）；多轮 case 实际查询场景以"读取整 conversation 一次性 judge"为主，几乎不需要 turn-level SQL 过滤；avoid scope creep（新表会让单/多轮路径分裂）。

### D2. Progress streaming 协议 ✅ 选 (a) UserWebSocketHandler

复用现有 `UserWebSocketHandler` user-level WS push channel 推送 `eval_progress` 事件（schema 见 [tech-design §2.1](tech-design.md)）。

**理由**：SkillForge 现有 WS 模型已经处理好 user-level routing + cleanup（SUBAGENT 异步派发 / chat / session_updated 都用同 channel）；引入 SSE 是第二种异步通信协议没必要。

### D3. Dataset 实体设计 ✅ 选 (a) 沿用 EvalScenarioEntity

M0 不引入新 entity；按 `agentId` 聚合 EvalScenarioEntity 即"dataset"。M3 真做 dataset versioning / tag / cross-agent 共享时再考虑独立 entity。

**理由**：M0 MVP "看到 case 列表"的 80% 价值来自 UI 上能列出 case，不需要新建实体；YAGNI；M3 实施时再决定（可能那时候 langfuse-style `Dataset` + `DatasetItem` 双 entity 是对的，但本期不预先决定）。

### D4. M0 stretch "分析 agent" 入口 ✅ 选 (a) 纳入 M0

scenario 卡片加"分析这个 case"按钮，点击调 SubAgentTool dispatch 已配置的 analysis agent，task 自动 fill "看 session X 的执行轨迹给优化建议"。

**理由**：复用 SubAgentTool dispatch + SUBAGENT-CONTINUATION（今天刚交付 ee3dd53），实现 ~30 行 backend wiring + 1 button；MVP 就能让"看分数 → 让 agent 给 follow-up 建议"一键到位，价值杠杆显著。

### D5（新增）. M3 是否引入 langfuse-style DatasetRun + DatasetRunItem 实体 ⏳

**调研 2026-05-04 发现**：langfuse 用 `DatasetRun + DatasetRunItem.traceId` 显式建模"experiment run 与 trace 的链接"。SkillForge 当前 `EvalRunEntity.scenarioResults` jsonb 把整 batch 结果塞到一个 jsonb，没有显式 RunItem 实体——**case → trace 链接、cross-run compare 都受这个影响**。

| 选项 | 优 | 劣 |
|---|---|---|
| **(a) M3 引入 `t_eval_run_item`**（fk run_id + scenario_id + trace_id + score） | 对标 langfuse / opik 标准 / case→trace 链接自然 / cross-run compare 容易 | M3 schema migration + scenarioResults jsonb 兼容迁移 |
| (b) M3 沿用 jsonb，从 jsonb 抽 traceId 渲染 link | 0 schema 改动 | jsonb 查询性能差，cross-run compare 要解 jsonb |

**待 ratify**（M3 实施前再拍板，MVP 不影响）。倾向 **(a)**，但规模决策可在 M0/M1 落地后基于真实使用频率再定。

## 7. 风险 & 边界

- **R1** EvalJudgeTool 跑出 compositeScore=0 / attribution=NONE 是真"判没分"还是 stub？M0 实施时 cross-check：随机抽 3 个最近的 EvalRun，看 scenario_results jsonb 是否真有非零 score + 非 NONE attribution。如果是 stub，M0 还需要补 judge 实际化（这条不在原 5 痛点）
- **R2** WS 推送 case-level 事件可能产生 high-frequency push（dataset 几百 case 时）—— M1 需 throttle / batch
- **R3** 多轮 case 在现有 PromptImproverService / SkillEvolutionService 也用到，M2 的兼容性要 audit 这两条路径
- **R4** Annotation queue（M3）涉及 ground truth 修改，需要 audit log + revert 能力，避免改坏

## 8. 估时与排期

| Milestone | 估时 | 档位 | 依赖 |
|---|---|---|---|
| M0 | ~1 天（含 R1 audit）| Mid | 无 |
| M1 | ~0.5 天 | Mid | 无 |
| M2 | ~1-2 天 | Full（schema + 协议）| M0 验证 judge 真出分 |
| M3 | ~3-5 天 | Full | M0 + M1 |

**建议排期**：MVP（M0 + M1）一次 sprint 做掉（~1.5 天）。V2（M2 + M3）等 P12 定时任务 MVP 之后再排（不阻塞 P12）。
