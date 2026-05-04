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

1. **看到 case 列表**（最先提，最痛）
2. **多轮 vs 单轮区分**
3. **看到进度**
4. **看到分数 + 建议**
5. **整体页面对标**

→ 这指引 PRD M0/M1 优先解 1+3+4，M2 解 2，M3 解 5。
