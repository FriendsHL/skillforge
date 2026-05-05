# EVAL-V2 MRD — 评测系统改造

## 1. 用户原始痛点（2026-05-04）

用户在 chat 直接报告 5 条：

> 1. 我不知道当前有哪些测试的 case（测试集、数据集）
> 2. agent 评测之后，我不清楚单轮任务评测和多轮对话的评测应该如何评测
> 3. agent 跑一次评测，我不知道正在进行的进度
> 4. agent 跑了一次评测之后，应该有一些分数或者一些建议（可以有分数，或者过程的 session 这个可以给到分析 agent，让他给意见也可以）。但是现在好像什么都没有
> 5. 感觉 eval 页面需要优化下，最好可以参考 langfuse、opik、coze 罗盘等项目他们的评测是如何做的

## 2. Audit 结果（grep + 代码确认）

| 痛点 | 现状 | 真伪 |
|---|---|---|
| 1. case 列表 | `EvalController` 有 `/eval/scenarios` GET；前端 Eval.tsx Scenarios tab 仅展示某 run 内 results（`runDetail.scenarioResults`），**不是独立 dataset browser** | ✅ 真 gap |
| 2. 多轮 | `EvalScenarioEntity` 字段 `task` 单 String + `oracleExpected` 单 String，**无 conversation_turns 字段**；judge tool 也只处理单 turn | ✅ 真 gap |
| 3. 进度 | `EvalController:73` 返回 202 + `status:RUNNING`；详情 GET 只能 poll `passedScenarios` 计数；无 WS/SSE | ✅ 真 gap |
| 4. 分数 / 建议 | 链路完整：`EvalJudgeTool` → `EvalOrchestrator:126` 写 compositeScore + attribution + judgeRationale；Eval.tsx:363 渲染百分比 + attribution 标签 | 🟡 部分误解 — 链路在；可能 judge LLM 没配 / attribution 默认 NONE 看着空；显示位置不显眼 |
| 5. UI 对标 | 现状：list + spark + scenario tab；缺 dataset 一等公民页 / compare runs / annotation / case-trace linking | ✅ 真 gap |

## 3. 对标平台特性摘要

> 以下基于 2026-05-04 web 调研（[langfuse core-concepts](https://langfuse.com/docs/evaluation/core-concepts) + [data-model](https://langfuse.com/docs/evaluation/experiments/data-model) + [opik concepts](https://www.comet.com/docs/opik/evaluation/concepts) + 中文 blog 关于 Coze Loop / 罗盘）。

### Langfuse 核心数据模型

```
Dataset (name, description, metadata)
  └─ DatasetItem (input, expectedOutput, metadata, sourceTraceId, status)
       └─ DatasetRunItem (datasetItemId + traceId + observationId)  ← 关键链接
            └─ Trace (input, output)  ← 实际执行轨迹
                 └─ Score (name, value, comment, type)
```

**关键设计**：
- **DatasetRun + DatasetRunItem** 显式建模 experiment 与 trace 的链接 —— 每次 experiment run，每个 dataset item 产生一个 DatasetRunItem，通过 `traceId` 关联到执行 trace。这是"case → trace 链接"的标准实现
- **Score universal 化**：支持 NUMERIC / CATEGORICAL / BOOLEAN / TEXT 四种类型，可挂 traces / observations / sessions / runs（SkillForge 当前只有 Number compositeScore，缺类型多样性）
- **4 种评分方法**：LLM-as-Judge / UI 手工评分 / Annotation Queues 结构化人工流程 / API+SDK 程序化检查
- **Dataset versioning**（2026-02 加）：查询特定时间戳的 dataset 版本，完整复现历史 experiment
- **Online + Offline 双轨**：offline 跑预定义 dataset / online 在 production 抓 trace 评估
- **virtual folder 组织**：用 `/` 分隔 dataset name，UI 自动按层级组织
- **Experiments via UI**：UI 内直接 prompt 实验 + side-by-side compare，不一定要写 SDK 代码

### Opik 核心思路

- **Dataset + Experiment + Metrics** 三件套（类比 langfuse 但术语略不同）
- **三种 evaluator 机制**：
  - **LLM Judge**：自然语言断言（配合 Test Suites）
  - **Built-in Metrics**：Hallucination / AnswerRelevance 等开箱即用 scorer
  - **Custom Metrics**：用户自定义评分函数（LLM-based 或 heuristic）
- **Test Suites vs Datasets+Metrics 双轨**：
  - Test Suites = "pass/fail per assertion"，开发期 behavioral validation
  - Datasets+Metrics = "numeric scores per metric"，production pipeline 质量追踪（RAG 系统场景）
- **Dataset versioning**（2026-02 加，跟 langfuse 同期）：自动关联 experiment 到具体 dataset 版本
- **Items 含 description 字段**：文档化 / 标注评测数据

### Coze 罗盘（loop.coze.cn）

- **Trace 自动评测**：自动 sample 在线 trace 数据（指定时间范围 / 条件），把 input/output 喂给评测引擎 —— **online evaluation 是亮点**（用户痛点 1 + dataset 持续填充）
- **数据回流到评测集**：online trace → 评测集 / 训练集，持续完善 case 库
- **多维度评分**：accuracy / conciseness / compliance / safety
- **多模型对比评测**（类 langfuse compare runs）
- **Prompt 版本管理 + eval**：Prompt 迭代闭环跟 eval 整合
- **Trace + Dataset + Bot evaluation 整合**：把 observability 和 eval 当一个产品做

### 三者共同思路（SkillForge 该学）

1. **Dataset 一等公民页面**：不能藏在 run 详情里
2. **Run linking to traces**：每个 case 执行结果可跳到具体 trace（SkillForge OBS-1/4 已有 trace，缺 link）
3. **Compare runs side-by-side**：跨 run/模型/prompt 版本对比
4. **Score 多类型 / 多源**：不只是单一 compositeScore Number
5. **Annotation queue**：human-in-the-loop labeling
6. **Online + Offline 双轨**：不只是 offline scenario，production trace 也可成 case
7. **Dataset versioning**：复现历史 experiment

## 4. SkillForge 现状对标

| 能力 | SkillForge 现状 | Langfuse | Opik | Coze 罗盘 | 备注 |
|---|---|---|---|---|---|
| Trace 数据 | ✅ OBS-1/2/4（root_trace_id 一等公民） | ✅ | ✅ | ✅ | SkillForge OBS-4 跨 agent 串联设计强 |
| Dataset 一等公民页面 | ❌（UI 缺 browser） | ✅ | ✅ | ✅ | M0 解 |
| Dataset versioning | ❌ | ✅（2026-02） | ✅（2026-02） | 🟡 | M3 解 |
| Eval run + result | ✅ EvalRun + scenarioResults jsonb | ✅ DatasetRun + DatasetRunItem | ✅ Experiment | ✅ | SkillForge 缺显式 RunItem 实体 |
| LLM-as-judge | ✅ EvalJudgeTool | ✅ | ✅ LLM Judge / Built-in Metrics | ✅ | |
| Score 多类型（NUMERIC/CATEGORICAL/BOOLEAN/TEXT） | ❌（只 Number compositeScore） | ✅ | ✅ | 🟡 多维度 | M3 考虑 |
| Built-in Metrics 库（Hallucination/AnswerRelevance） | ❌ | 🟡 | ✅ | 🟡 | V2 之后 |
| Custom Metrics（用户自定义 evaluator） | ❌ | ✅ | ✅ | 🟡 | V2 之后 |
| Compare runs side-by-side | ❌ | ✅ | ✅ | ✅ | M3 解 |
| Annotation queue（人工标注） | ❌ | ✅ | ✅ | 🟡 | M3 解 |
| Real-time progress | ❌（只能 poll） | 🟡（弱） | 🟡 | 🟡 | M1 解，**SkillForge 反而比对标平台领先** |
| Multi-turn case | ❌ | ❌（单 input/output） | ❌ | 🟡 部分 | M2 解，**SkillForge 此处可超越对标** |
| Case → trace 链接 | 🟡 scenarioResult 有 traceId 字段但 UI 没用 | ✅ DatasetRunItem.traceId | ✅ | ✅ | M3 解 |
| Online evaluation（production trace 自动 sample） | ❌ | ✅ | 🟡 | ✅ Coze 强 | V3+ |
| Trace 数据回流到 dataset | 🟡 P2-6 SessionScenarioExtractor 手动触发 | ✅ Datasets from Production | ✅ | ✅ Coze 强 | M3 stretch / V3 |
| Test Suites（pass/fail 断言） | ❌ | ❌ | ✅ Opik 独有 | ❌ | V2 之后 |
| Virtual folder / dataset 分组（`/` 命名） | ❌ | ✅ | ❌ | ❌ | M3 nice-to-have |

## 5. 用户当前最强诉求（按 chat 顺序 = 优先级信号）

1. **看到 case 列表**（最先提，最痛）—— M0+M1 已交付（commit 47b331c）
2. **多轮 vs 单轮区分** —— M2 待启
3. **看到进度** —— M0+M1 已交付
4. **看到分数 + 建议** —— M0+M1 已交付，M3c 重构归因闭环
5. **整体页面对标** —— M3a-g 持续推进
6. **闭环完整：评测集 → 任务 → 执行 → 评价 → 归因 → 迭代建议**（2026-05-05 用户用了 M0+M1 后新提出）—— M3a + M3c 解

→ 痛点 6 是 PRD 重写的核心动因（详见 PRD §1）。

## 6. M0+M1 试用反馈（2026-05-05，PRD 重写动因）

用户 2026-05-04 试用 M0+M1+Q123 后提出：

### Gap A — Dataset UI 信息不足
DatasetBrowser 卡片只显 task 一行；ScenarioDetailDrawer 没显示 description / oracle / setup.files / toolsHint / tags。用户原话："只能看到地址"（实际是 task 文本但信息密度低，看着像只是个文件路径）。→ M3b 解。

### Gap B — Analyze 应绑 EvalRun 不是 Scenario
当前 AnalyzeCaseModal 通过 `source_scenario_id` 关联 scenario。但同一 scenario 跑过多次 run（不同 score / attribution），分析对象应该是 (scenario, run) 组合而非仅 scenario。→ M3c 解（重构为独立关联表 t_eval_analysis_session 含 task / item / scenario 三种 analysis_type）。

### Gap C — 评测闭环（用户主动设计）
用户描述："评测集 + agent → 任务表 → 执行（复用 session/trace/span）→ 评价（score + 各维度）→ 归因 → 迭代建议"。这是把 EVAL-V2 从"测试工具"升级到"完整迭代闭环"。→ M3a + M3c 联合解。

### Gap D — t_session 污染问题
用户问"session/trace/span 表是不是要加字段区分 online vs eval"，主会话 audit 后发现：eval 跑动会创建 t_session 行 + t_llm_trace 行，**污染**用户 chat sessions 列表 / OBS trace dashboard / Cost 页 / Compaction 触发 / Startup recovery 5 处。→ D10 决策：t_session + t_llm_trace 加 `origin VARCHAR(16)` 字段，partial index 高效过滤。spawn child 时父→子继承 origin（跟 OBS-4 root_trace_id 复制逻辑同源）。→ M3a 内一并解。

## 7. 持久化策略（已交付 + 计划）

scenario 三源持久化策略（D3 + D7 + Q2+Q3 落实结果）：

| 来源 | 持久化 | 状态 |
|---|---|---|
| 系统内置 | classpath JSON `eval/scenarios/*.json` | 已有，read-only |
| 用户/Tool 新增 | home dir JSON `${SKILLFORGE_HOME}/eval-scenarios/*.json` | Q2+Q3 已交付（commit 3554569）|
| session 提取 | DB `t_eval_scenario`（agentId NOT NULL） | 已有（P2-6 / EvalScenarioEntity） |

**已知 trade-off**：scenario 持久化双轨（per-agent → DB / global → JSON）。M3g 真做 dataset versioning 时考虑统一（参 PRD §11 R5）。

任务执行的持久化（D6 / D7 / D8 / D10）：

| 数据 | 落库 | Milestone |
|---|---|---|
| 任务调度元数据（agent / dataset_filter / status / counts） | t_eval_task（rename from t_eval_run） | M3a |
| per-case 评价指标（score / status / loops / tools / latency / attribution / rationale / final output） | t_eval_task_item（替代 jsonb scenarioResults） | M3a |
| 任务跑动的实际对话轨迹 | t_session + t_llm_trace + t_llm_span（origin='eval'） | M3a 复用 OBS-1/2/4 |
| 归因汇总 | t_eval_task.attribution_summary + improvement_suggestion 字段 | M3c |
| 归因 chat session 关联 | t_eval_analysis_session 独立表 | M3c |
| 人工标注 | t_eval_annotation 独立表 | M3f |
