# 提案 — bad-case 收割 + G3 预测对账

> 状态：**scope 已对齐（Full），待 ratify**。两块**分开两期**（D-3）：先 bad-case 收割出真赢家，G3 之后单独立项。
> 背景：干净 evolve 重跑（2026-06-03）两轮无赢家——根因 A/B 量在 benchmark/role 场景，候选却修生产真失败（Edit/Grep 前置），靶子错位。前面所有件（G4/G5/concern#2/infra 摘除/P1）都在等这个真信号。
> 关键事实经一次 codebase 取证确认（见"现状取证"节），方案据此从初版的 Mid-Full 修正为 **Full**。

---

## 名词：oracle 是什么

一次运行结束后判 pass/fail 的**判分函数**（判卷老师）。没有 oracle 就没法自动比 A/B 谁赢。现有 oracle 形态见取证节第 4 条。

---

## A. bad-case 收割（让 A/B 量在真失败上）

### 目标
把**真失败**转成可复现、隔离的 eval 场景 → A/B 量在真失败上 → 候选修对了才量得出真提升 → evolve 出真赢家 → P1 有东西可采 + 盲测可验证。

### 收割单元（D-2 拍板）
**按 ERROR 聚类 → 每个 cluster 选一个代表 session → 同任务在沙箱里跑 N 轮 → 取"失败模式复发率"**。
- 单轮有噪声；多轮复发率（baseline 比如 7/10 轮命中那条错）比单次 pass/fail 稳。
- 数据来源：G5 holistic 分析已按 (工具+错误签名) 跨 session 归好类（Edit old_string ×44/7session、Grep path ×49/11session），每组带 exampleSessionIds + rootCause。先只用 holistic 聚类（最对靶）。

### oracle 形态（D-1 / D-4 拍板）
- **A = 行为型（起步只做这个）**：重跑任务 → 查这次运行的 tool-call span → 那条**错误签名**出现没（如 Edit `old_string not found`）。出现=该轮复发，N 轮取复发率。直接对应"那类错没了=修好了"，便宜（查 span，不用 LLM judge）、精准。
- **B = 效率型（以后再加）**：pass = 完成 且 loop 轮数/延迟低于阈值。针对"能完成但绕一大圈"那类。
- C = outcome 型（LLM judge）：不做。

**oracle 判据 = 结构化错误签名匹配器**，例：
```json
{ "tool": "Edit", "errorSignature": "old_string not found",
  "passWhen": "no_match", "rounds": 10 }
```
判一次运行：扫所有 tool span，命中 (tool+error 含签名) → 该轮复发。

### 真文件安全（你的命门问题，已取证）
**不是"拿真 session 去执行改真文件"。** harness 现有完整沙箱：每场景跑在独立 temp 目录、Read/Write/Grep/Glob 全是 Sandboxed 版（越界拒）、跑完整棵删。agent 够不到真仓库。**前提是给沙箱铺一份 fixture**（见下"现状取证"第 5 条 + 缺口①）。

### 盲测约束（贯穿）
收割逻辑**只做通用动作**：从失败 session 重建 task + 文件 fixture，跑多轮量复发率。**不把任何具体修法（例如"Edit 前先 Read"）写进 fixture / oracle / prompt / 文档** —— 留给 evolve loop 自己发现。

---

## 现状取证（2026-06-03，Explore agent 带 file:line）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 场景怎么跑 agent | `AbEvalPipeline.runSingleScenario:927` → `AgentLoopEngine.run` 包 Future + 超时；task 里 `/tmp/eval/` 前缀替换成 sandbox root |
| 2 | 文件系统隔离？ | **有完整沙箱**。`SandboxSkillRegistryFactory:42` 每 run 一个 `${tmpdir}/eval/{runId}/{scenarioId}`；`SandboxedFileReadTool:56` 等做 path-prefix 越界拒（非 OS chroot）；`cleanupSandbox` finally 删整棵（`AbEvalPipeline:1013`）|
| 3 | EvalScenarioEntity 字段 | 28 字段。**无自由 JSON 字段**。oracle 靠 `oracleType`(exact_match/contains/regex/llm_judge) + `oracleExpected`。source_type ∈ {benchmark, session_derived, manual}。session_derived 由 `SkillCreatorService:750` 造，**只设 task，不带 fixture** |
| 4 | 现有 oracle / pass 判定 | `EvalJudgeTool`：composite = 0.7*outcome + 0.3*efficiency；outcome 由 oracleType 算；pass = composite ≥ 40（`AbEvalPipeline:74`）。tool 错误信号已被 `ScenarioRunResult.applyToolCallSignals:139` 抓（skillExecutionFailed）→ 喂 AttributionEngine |
| 5 | fixture / 快照机制 | **fixture 注入有**（`scenario.setup.files` Map<path,content> 开跑写进沙箱，`AbEvalPipeline:968`）；**从 session 抓文件快照没有**——session 只存消息历史（messagesJson），不存工作区文件 |

---

## 三个真缺口（决定 = Full）

| 缺口 | 现状 | 要加 |
|---|---|---|
| **① fixture 来源 + 持久化** | session 不存工作区文件；EvalScenarioEntity 无 JSON 字段；session_derived 场景无 fixture | (a) 从 session 的 FileRead/Edit span **重建** fixture（retroactive、零等待、够用就收不够就跳）；(b) 加列/表持久化 setup.files（**触 schema**）|
| **② 行为型 oracle** | 只有 exact/contains/regex/llm_judge，无"按 tool 错误签名判" | 加新 oracleType + 签名判据存储；原料有（applyToolCallSignals 已抓 tool 失败）|
| **③ 多轮复发率** | harness 单轮跑一个场景 | 加 repeat N 轮 + 复发率聚合（D-2 要的口径）|

**结论**：bad-case 收割是正经 **Full** 功能（触 schema + 核心 eval harness + 跨 3 块），走 Full pipeline。

---

## B. G3 预测对账（你说的"预测结果"）— 独立第二期（D-3）

> bad-case 跑通出真赢家后再立项，这里只存设计骨架。

每轮迭代带**可证伪预测** `{targetProblem, flipToPass:[场景id], riskToFail:[...]}`；A/B 后对账真实翻转：
- 预测准 → targetProblem 真被修，高信心
- 预测说修 Y 但 Y 没翻 → **要么修法不对、要么归因分析错** → 降信心 + 下一轮换思路

依赖 bad-case 提供"可命名真问题 + 可复现场景"（flipToPass 才有真 scenarioId 指）。档位待 G3 立项时定（候选加字段 + 对账纯函数 + 反思接线，可能 Mid）。

---

## 顺序
1. **bad-case 收割（Full，先做）**——本提案主体。
2. **G3 预测对账（独立第二期）**——bad-case 出真赢家后。
3. **P3 benchmark north-star（贯穿）**。

## 已拍板（本轮）
- **D-1**：A=行为型 / B=效率型；起步只做 A。
- **D-2**：ERROR 聚类 → 选一个代表 session → 多轮跑取复发率。
- **D-3**：G3 跟 bad-case 分两期。
- **D-4**：oracle 判据 = 错误签名匹配器；需 schema 存储 → **Full**。

## Spike 结果（2026-06-04，DB 实测，de-risk 完成）

承重假设"fixture 能否从 span 重建"——**实测强正向，retroactive 路线成立**：

| 失败类 | 现有量 | 重建覆盖 | 备注 |
|---|---|---|---|
| **Edit `old_string not found`** | 42 fails / 8 session | **42/42 (100%)** 同 session 前有 Read/Write 同文件 | 41 条 prior Read 内容**全 < 40KB 无截断**（fix-path 保真无损）|
| **Grep `Path is not a directory`** | 53 fails / 15 session | **53/53** 路径已知，沙箱建出该文件即复现 | 内容可选（有 prior Read 就补做 fix-path）|

取证要点（`t_llm_span`）：
- tool 调用全存：`kind='tool'` + `name`（Edit/Grep/Read…）+ `input_summary`（**完整 JSON args**，含 file_path/old_string，实测可 `::jsonb` 解析）+ `output_summary`（Read 文件内容，~40KB 上限）+ `error`/`error_type`。
- fixture 内容源 = 同 session 该文件的**前一次成功 Read/Write 的 output/args**。

**结论**：retroactive 从 span 重建 fixture 可行（不需要"以后跑 session 才快照"的 fallback 当主路）。现有 harvest pool ≈ **23 个不同 session**（8 Edit + 15 Grep），量不大但够 seed 一批场景，且随 session 增长。

盲测约束守住：覆盖率全程按**通用条件**（"同文件前有内容源"）算，没在任何地方编码"先 Read 再 Edit"。

## 待 ratify 时定的细节（fixture 覆盖率已由 spike 答掉）
- **路径 rebasing**：bad case 是绝对路径 `/Users/youren/...`，harvester 要 rebase 进 sandbox root（harness 已有 `/tmp/eval/` 替换先例，扩展即可）。
- N 轮的 N（10？）+ 复发率→pass/composite 怎么映射（直接当 outcomeScore？）。
- fixture 存列还是存表（实测内容 < 40KB，单列 TEXT/JSONB 够；多文件 fixture 用 JSONB map）。
