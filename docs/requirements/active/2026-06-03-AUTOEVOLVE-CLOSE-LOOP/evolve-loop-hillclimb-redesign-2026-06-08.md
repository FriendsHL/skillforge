# evolve loop 重构 — 从「横扫 issue」改成「朝整体爬坡」（2026-06-08）

> **来源**：用户 2026-06-07/08 对齐。现行 `evolve-loop.workflow.js` 是「遍历 issue 清单，每条改一次，扫完就停」；用户要的是「朝整体 benchmark（大盘+失败场景加权）持续爬坡，达标/收敛/跑满才停，agent 每轮看全部线索+历史自主决策本轮怎么调」。
> **档位**：Full（红灯：核心 workflow + 候选生成 agent prompt + A/B 优化目标 + 跨模块）。
> **状态**：✅ **已 ratify（2026-06-08）** + ✅ **阶段 A 已实现并交付（2026-06-08，Full pipeline）** —— 用户拍定 §0 五决策 + bad-case 边界（基础设施失败排除 / 行为模式失败要新 oracle）。阶段 B 单独立项（见 §4 + backlog `EVOLVE-BADCASE-SENSITIVITY`）。
>
> **实现回写（2026-06-08，交付事实见 delivery-index 首行）**：
> - **weightedScore 计算位置偏离本文 §5 改动3**：本文把它列在 `AgentEvolveAbEvalService`，实现时改放 **`GetAbResultTool` read 时计算**（`computeWeightedScore` 按在场子集重归一 + 空 harvest 退化）。理由：per-subset rate 已持久化在 ab_run 行，read 时用 `EvolveThresholdProperties` bean 直接组合即可——**无需加列、单一来源、不动 service**。`baselineWeightedScore` 同法从 baseline 侧 rate 算，喂 JS 的 keep 比较。
> - **history grounding**：GetAbResultTool 额外返 `perScenarioFlips`（regressed/improved，Java 用 `AbEvalPipeline.isPass` 判，各 cap 20 + total 计数），workflow 取 `regressed` 进 history 的 `perCaseRegressed`（JS 不重复实现 pass 阈值）。
> - **migration**：仅 V151 UPDATE evolve-candidate-gen system_prompt（不加列）。
> - **冒烟结果**：live run `c1348f26`（agent3, maxIter=5）S1-S7 = 6 PASS / 1 PARTIAL / 0 FAIL。weightedScore 真爬坡 34.67→37.33→53.33→60→45.33（iter5 回退被正确拒绝，best=iter4）。
> - **S2 取证 follow-up（观测性，非功能缺陷）**：workflow `agent()` 子会话 prompt 不落库，live 无法贴 candPrompt 原文（单测 candPromptCarriesGlobalContext 绿 + live 行为间接证实）。**backlog**：在 RecordIteration step_output_json 回存当轮 candPrompt 快照，让 S2 可 live 取证。

## 0. 用户拍定的 5 个决策

| # | 决策 | 选定 |
|---|---|---|
| 1 | 停止条件 | **三个全开**：达标 OR 连续 N 轮不进步（收敛）OR 跑满 maxIter |
| 2 | 每轮改几个面 | **agent 自选**（放开 allowedSurfaces 白名单，保留代码兜底防越界） |
| 3 | 靶子修不修 | **修**（见 §4 scope 拆分：阶段 A 建框架，阶段 B 补敏感场景） |
| 4 | opt-report 重跑频率 | **只跑一次**（开头）。report 是静态线索库，"第二轮怎么改"靠 report+上轮配置+上轮评分，agent 自己推 |
| 5 | 优化目标 | **大盘为主 + 失败场景加权**：主信号 = 整体 benchmark 分；失败场景（确定性 oracle）加重权重让信号更敏感 |

## 1. 形态对比

**现在（横扫，issue 当循环变量）：**
```
opt-report → issues=[i1,i2,i3]（排序）
for (每个 issue) { 改这条 → A/B(vs best) → 留/弃 }
停 = issues 扫完（或撞 maxIter）   ← 没有"达标才停"，每条只试一次
```

**目标（爬坡，轮次当循环变量）：**
```
opt-report → issues 全部当静态线索库（只跑一次）
best = active 配置；noImprove=0
for (iter=1..maxIter) {
   候选生成(全部 issues + 当前 weightedScore + 完整历史[改了啥/整体&per-case 涨跌])
       → agent 自主判断本轮整体最该调哪面、怎么调
   A/B(candidate vs best) → weightedScore
   if weightedScore 达标阈值: keep + break          // ① 达标停
   if weightedScore > best: best=candidate; noImprove=0
   else: noImprove++
   if noImprove >= N: break                          // ② 收敛停
}                                                      // ③ 跑满 maxIter
return best + 完整轨迹（最优方向）
```

## 2. weightedScore 定义（决策 5 落地）

复用现有 `AgentEvolveAbEvalService` 的 target/general split（F2/F3 刚修过的 per-subset rate）：

```
weightedScore = w_general * generalPassRate + w_harvest * harvestPassRate
```

- `generalPassRate`：general 子集（标准 benchmark 场景）综合 pass rate
- `harvestPassRate`：harvest/失败子集（确定性 oracle 优先）综合 pass rate
- 默认 `w_general=0.6 / w_harvest=0.4`（大盘为主），可配
- **harvest 子集为空 → w_harvest=0，退化为纯大盘**（向后兼容 + 阶段 B 前的现状）
- 两个子集都用 F2 的 null 哨兵：某子集 0 measured → 该项不计入（不是当 0 拖低）

**keep 判据**（替代当前 target dual-criteria 作主判据）：
```
keep = (candidate.weightedScore > best.weightedScore + minImprovePp)
       AND (candidate.generalPassRate >= originalGeneral - anchorErosionFloorPp)  // 大盘护栏(复用 F6)
```
即：加权分要真涨 + 大盘不能为了刷 harvest 而崩。`minImprovePp` 防 temp=0 噪声（默认 0 或小正数）。

> 注：现行 `GetAbResult.wouldPromote`（agent 面 target dual-criteria）**不删**，降级为 advisory 字段；爬坡 keep 改看 weightedScore。两者并存，RecordIteration 都记，便于对比。

## 3. 停止条件（决策 1，参数化）

```
① 达标：weightedScore >= targetWeightedScore（可空 → 不靠达标停，纯爬坡）
② 收敛：连续 noImproveStreakLimit 轮没有新 best（默认 3）
③ 跑满：iter >= maxIter（默认 10）
```
三者先到先停。用户"run 10 遍给最优方向"= 阈值留空 + maxIter=10 + 收敛兜底。

## 4. Scope 拆分（诚实分阶段，避免做虚）

> 「失败场景加权」要真敏感，前提是有一批"能反映 agent 改进的确定性 oracle 失败场景"。现 harvested 只有 1 个确定性 oracle（badcase-grep，Edit/Grep 类），跟多数 issue（如 Web 调研成本）不同类。所以靶子修到位本身是子工程。

| 阶段 | 内容 | 验证什么 |
|---|---|---|
| **A（本次 Full）** | 循环重构 + 候选生成 prompt 改 + 三停止条件 + weightedScore 框架（含 w_harvest=0 退化）。**用现有场景跑通爬坡机制** | loop 形状对不对：朝加权分迭代、停止条件触发、出最优方向、agent 看全局自主决策 |
| **B（后续 follow-up）** | 扩充「能反映各类改进的确定性 oracle 失败场景」（harvest 覆盖更多失败类型 / 构造 oracle），让 harvestPassRate 真敏感 | 信号质量：让加权分"爬得动" |

**为什么拆**：A 改循环骨架（确定性、可单测、live 验形状）；B 改尺子敏感度（要收割/构造场景，另一种活）。混做则 temp=0「爬不动」会盖住「结构对不对」的验证，reviewer 也抓不住重点。**本设计文档只覆盖阶段 A。**

## 5. 阶段 A 改动分解

### 改动 1 — 循环结构（`evolve-loop.workflow.js`）
- opt-report 跑一次（现状保留），issues 全部进静态线索库 `allIssues`（不再 `selectAndRank` 后逐条 pop —— 排序仍可保留供 agent 参考）
- 主循环 `for (var iter=1; iter<=maxIter; iter++)`（issue 不再是循环变量）
- 每轮：候选生成(allIssues + best + history) → mergeBundle → GetCandidateDiff → TriggerAbEval(vs best) → 轮询 GetAbResult → 算 weightedScore → keep 判断 → RecordIteration → carry-forward
- 三停止条件 + 返回 best
- win-streak 基线缓存（F4）保留：keep 后下一轮 candidate-only

### 改动 2 — 候选生成 prompt（`candPrompt` + `evolve-candidate-gen` 的 system_prompt）
- candPrompt 输入从 `issue=<单条>` 改成：
  ```
  allIssues=<全部线索 JSON>
  currentBest={weightedScore, generalPassRate, harvestPassRate, bundle}
  history=[{iter, changeDesc, weightedScore, delta, perCaseRegressed:[...], kept, keepReason}, ...]
  任务：综合以上，判断本轮整体最该调哪面、怎么改，把 weightedScore 往上推
  ```
- 放开 allowedSurfaces（agent 自选 prompt/behavior_rule/skill），`filterToAllowed` 兜底保留防越界
- `evolve-candidate-gen` 的 system_prompt（实现时定位其 seed migration，疑似 V135 附近）从"执行单条 issue"改写成"看全局自主决策本轮方向"
- **history 必须含 per-case 涨跌**：GetAbResult 已返 perScenario，workflow 现仅把整体分塞 priorEvalReport → 扩成带 perScenario regressed 列表（接上之前提的 grounding 缺口）

### 改动 3 — weightedScore（`AgentEvolveAbEvalService` / `GetAbResultTool`）
- GetAbResult agent 面响应加 `weightedScore`（用 §2 公式，权重从 `EvolveThresholdProperties` 读）+ 回显 `generalPassRate/harvestPassRate`（部分已有）
- weightedScore 计算复用现成 per-subset rate（F2/F3 的 target=harvest / general split），无需新查询
- keep 判据迁移：workflow `decideKeep` 改看 weightedScore（wouldPromote 降 advisory）

### 改动 4 — 参数（`EvolveThresholdProperties` + `application.yml`，复用上批新建的类）
新增键（`skillforge.evolve.thresholds.*`）：
- `weight-general: 0.6` / `weight-harvest: 0.4`
- `min-improve-pp: 0`（keep 防噪声）
- `no-improve-streak-limit: 3`（收敛停）
- `target-weighted-score:`（空 = 不靠达标停）
- maxIter 沿用 API 参数（默认 10）

## 6. 改动文件清单（阶段 A）

1. `skillforge-server/src/main/resources/workflows/evolve-loop.workflow.js`（循环重构 + prompt + weightedScore keep + 停止条件）
2. `evolve-candidate-gen` system_prompt 的 seed migration（新 V1xx UPDATE，实现时定位现有 migration 号）
3. `AgentEvolveAbEvalService.java`（weightedScore 计算）/ `GetAbResultTool.java`（响应加 weightedScore + history 用 perScenario 字段）
4. `EvolveThresholdProperties.java` + `application.yml`（新键 + @Validated）
5. `meta.description` + 注释更新（workflow 形态变了）
6. 测试：EvolveLoopWorkflowTest（爬坡循环 / 三停止 / agent 自选面 / weightedScore keep）+ AgentEvolveAbEvalServiceTest（weightedScore 公式 + 空 harvest 退化）+ GetAbResultToolTest（weightedScore 回显）+ EvolveThresholdPropertiesTest（新键默认值/校验）

**不在阶段 A**：扩 harvest 场景覆盖（阶段 B）、dashboard 轨迹可视化调整（如需另起）、SemanticDeltaPanel FE WIP（保持原样不碰）。

## 7. 冒烟用例（部署后 qa-reviewer 执行，全 PASS 才 commit）

> 早失败：server 起不来/401 = BLOCKED 环境；workflow 步骤 `unknown tool` / schema 失败 = FAIL。

- **S1 循环不再被 issue 数决定**：触发 evolve run（maxIter=5）→ run 完 `summary_json.trajectory` 轮数应趋向 maxIter 或停在收敛点，**不等于 opt-report 的 issue 数**（除非恰好收敛）。证据：trajectory 长度 + 每轮 keepReason。
- **S2 每轮 agent 看到全部线索 + 历史**：取某轮 `subagent_dispatch` 的 prompt → 应含 `allIssues`（多条）+ `currentBest` + `history`（iter≥2 时非空，含 perCase）。证据：prompt 原文。
- **S3 weightedScore 落账 + 驱动 keep**：A/B 行 / RecordIteration 含 weightedScore；某轮 weightedScore 升过阈值则 kept=true。证据：SQL 取 trajectory 的 weightedScore + kept。
- **S4 三停止条件各能触发**（单测层面，live 至少验一种）：收敛停（连续 N 轮无新 best → break，trajectory 长度 < maxIter 且尾部连续 kept=false）。
- **S5 空 harvest 退化**：harvest 子集为空时 weightedScore == generalPassRate（w_harvest=0），不崩。证据：单测 + 一条 live A/B 的 weightedScore vs generalPassRate 比对。
- **S6 单测全绿**：`mvn -pl skillforge-server -am test` Tests run N, Failures 0, Errors 0 → BUILD SUCCESS 原文。
- **S7 出最优方向**：run 完 `summary.best` 是全程见过的最高 weightedScore 配置（不是最后一轮）。证据：best.weightedScore == max(trajectory.weightedScore)。

## 8. 风险 / 开放点（诚实）

- **temp=0 爬不动**：同输入 agent 每轮可能产相似候选 → 分不动。靠 history 上下文（"上轮这么改没用"）制造差异，但这是最不确定处，跑了才知道。**阶段 A 验的是"结构对"不是"一定爬得高"**。
- **时间成本**：每轮一次真 A/B（agent loop），10 轮可能 1 小时+。LLM 评测硬成本。
- **尺子钝**：阶段 A 用现有场景，大盘多为 llm_judge、harvest 仅 1 个 → 爬坡可能原地。**这正是阶段 B 要解决的**，阶段 A 不假装解决。
- **keep 判据迁移**：weightedScore 取代 dual-criteria 作主判据，wouldPromote 降 advisory —— 要确保 RecordIteration / dashboard 读两者都不崩（向后兼容旧 run 无 weightedScore 字段）。

## 9. 不变量自查
- 不触碰 ChatService / SessionService / AgentLoopEngine / compact / LLM provider → persistence-shape + identity-column 不触发
- orchestrator 路径（EvolveController orchestrator 分支 + V144/V145）**不动**（workflow 路独立演进）
- migration 仅 UPDATE 现有 agent system_prompt（不加列，不动 t_session_message）
- 不碰 skillforge-dashboard（FE WIP 原样）

## 10. 验收点
- [ ] 阶段 A 改动 1-4 落地，文件清单外零触碰
- [ ] S1-S7 冒烟全 PASS（含 live 验循环不被 issue 数决定 + weightedScore 驱动 keep）
- [ ] mvn 全模块绿
- [ ] orchestrator 路径行为字节不变
- [ ] 阶段 B（失败场景敏感度）明确记入 follow-up，不在本次
