# EVOLVE-CANDIDATE-GROUNDING — 候选 per-badcase grounding（EVOLVE-JUDGE-GROUNDING Phase 2）

> 创建：2026-06-18
> 状态：**Phase 2 已交付**（2026-06-18，commit `775fe4df`）。**真因（观察 3 深挖，更正观察 1/2 判断）= FR-C7 A/B 预算闸**：agent 3 累计 A/B 已达 cap=30 → **永久冻结**，evolve run 在它上面跑不了 A/B（配置 `skillforge.evolve.ab-budget-per-run` 名义 per-run、实为 **per-agent 终身累计**）。候选生成其实 OK（最小对靶 + reflect 生效），iter1 还出过 **+25pp/0回归** 候选（惜 decideKeep 仍 kept=false，次要）。~~下一步：换有预算的 agent / 调高 cap / 重审 cap 语义~~ → **FR-C7 已修（2026-06-24，commit `feat/frc7-window-runworkflow`，V165）**：终身累计 → 滚动 168h 窗（越界回落、保 CRIT-1 防绕过）+ 索引 + Main Assistant 绑 RunWorkflow。**live 验证 agent 3 解冻**（全历史 A/B=32 但窗口内=8 < cap30）。**✅ 整条调查收口（观察 4，2026-06-24，run `7f34d911`）：解冻后 evolve 多轮跑通、出第一个真赢家（iter1 +4.08 kept），decideKeep 验证正常（keep 真赢家、拒回归）——真因就是 FR-C7 冻结,decideKeep 无 bug。**
> 模式：Full（触碰核心 evolve-loop.workflow.js + 候选生成工具 + V-migration prompt；属核心测量/进化层）
> 前身：[EVOLVE-JUDGE-GROUNDING](../2026-06-17-EVOLVE-JUDGE-GROUNDING/index.md) Phase 1（配对判据，已交付）的 Phase 2。

## 摘要

Phase 1 把"判据噪声"治好了（配对 net-wins），但 **0 赢家依旧**——live run cba0878f 实证候选**主动制造回归**（iter2 改进 1 / 回归 8 / net -7）。根因（勘察确认）：候选从 opt-report 聚合线索生成，**真实失败场景详情（errorSignature/task/rationale）从没喂进候选生成**，leaf 蒙眼改整面 → 改一处崩八处。

**决策（我定 + architect review 修订）= 方案 A「grounding-in-context」+ C-seam「最小 delta 编辑」**，不做侵入式 fan-out（B）。

architect review 关键修正：纯 A（喂失败详情让 leaf 瞄准）**上限低**——live 的 net -7 是"整面编辑爆炸半径",瞄得准 ≠ 刷子窄。故必须叠加 C-seam（最小/加性编辑收窄爆炸半径）+ 接上一条被丢弃的高价值反馈回路。

## 4 个改动（详见 tech-design）

1. **接回被丢弃的反馈回路**（最高性价比）：`ReconcilePrediction` 结果 + `perScenarioFlips.regressed[].rationale` 已算但从没穿回下一轮 leaf history（现只塞 bare 场景名）→ 穿回去。
2. **喂 capped per-badcase 失败详情**：扩 `ListActiveHarvestedScenarios` 返投影截断（errorSignature + 一行 task + extractionRationale，**不含** fixtures/全文）→ 进 leaf prompt，V151 改"先诊断后改"。
3. **prediction 对已知失败 id 集 ground**（非瞎猜 id）+ 候选盖 targetScenarioId 因果链。
4. **C-seam：最小 delta / 加性编辑约束**（GenerateCandidate 优先追加靶向 rule 而非重写、prompt 限 diff）——直击爆炸半径。

## 不做（边界）

- 不做 B（per-badcase fan-out / 改"一轮一 bundle"基数 + A/B/keep 记账）—— review 论证 B 未验证为解（fan-out 仍是整面编辑、仍各自炸）。
- 不重开"整-agent 一个 bundle 一个分"设计（AUTOEVOLVE-AGENT-LEVEL-BUNDLE）—— 见 H2。

## 退出标准（2026-06-18 用户 ratify）

A+C 上线后，用 Phase 1 判据作尺，agent 3 跨 **≥3 轮干净 evolve run**：若中位 net-wins 仍 ≤0（0 赢家）**且**净回归没比 live 基线（-1/-7）明显降 → 判 A+C 不够，**升级**。

**H2（升级方向，非本需求）**：升级时**重开 bundle 设计**（per-scenario 子打分 / 限定编辑范围），**不是**反射式上 B。重开旧设计是用户决定。

## 观察记录（退出标准 ≥3 轮）

### 观察 1/≥3 — 2026-06-22，run `bbe8a4dd`（agent 3，maxIter=1）

**先肯定：管道/收尾层已修好。** 这次 run 干净跑完：opt-report（5 issue）→ GenerateCandidate → A/B（5 target + 45 regression = 50 场景跑完）→ ReconcilePrediction → RecordIteration → workflow 收尾，~7min，**没像历史(06-05)那样卡 RUNNING**（那批 active/RUNNING 孤儿是旧代码遗留）。

**但候选仍是负优化，0 赢家**（A/B run `1b71ca1e`）：

| 指标 | baseline | candidate | 差 |
|---|---|---|---|
| **target（5 个该修的 badcase）** | **80%** | **40%** | **−40pp** ❌ |
| regression（45） | — | — | +4.4pp |
| 总体 | 74% | 74% | 0 |

翻挂的 2 个 target（oracle 理由实证）：
- **Open Source Eval Platform Review**（多步调研报告）：baseline 70 PASS → candidate **0 FAIL**，理由"agent 根本没完成任务、只说自己缺资源" = **直接摆烂没产出**。
- **badcase-grep-81f62bbc**（多轮工具操作 behavioral oracle）：57 PASS → **30 FAIL**（干净完成 2/5 → 0/5）。

**根因（关键）**：本轮 issue 是"token 效率"类。候选生成器（evolve-candidate-gen）的"修法"是**糊上一整段通用强约束**：「输出严格限长 / 超 10 轮压缩丢细节 / 单任务搜索最多 15 轮」（候选 `12524c53`，prompt 448→669 字，还带"冗余冗余"错字）。这些**通用紧箍咒把多步任务憋死** → 在自己的靶场景上自爆。

**这直接证伪了 Phase 2 的 C-seam(#4)假设**：候选**不是**"最小/加性/对靶编辑",而是**大段通用重写**。说明：
- C-seam（GenerateCandidate 优先追加靶向 rule、限 diff）**没真生效** —— 候选仍在做整面宽泛改写。
- 候选生成**对回归无感知** —— 不知道这些全局约束会打挂 target 场景（grounding #2/#3 喂了失败详情，但没喂"这些能力必须保住")。

**结论（1/≥3，但机制极清晰）**：0 赢家的根**不在判据、不在管道**，在**候选生成质量**——拿任务完成度换 token 效率的越界改动。倾向不必凑满 3 轮即可判 **Phase 2 (A+C) 不够，需升级**。

### Phase 3 / H2 候选修复方向（待立项）

1. **候选生成强制"最小 + 对靶 + 回归感知"**：evolve-candidate-gen prompt 硬约束「只做针对该 badcase 失败行为的最小编辑，**禁止加可能影响其它任务的通用全局约束/紧箍咒**」；并把 **target 场景摘要喂给候选生成**（"这些能力必须保住，改坏即废"）。
2. **质疑 issue 适配性**：opt-report 产"token 效率"这类**宽泛 issue** 是否适合用"加 prompt 规则"来修 —— 宽泛 issue → 宽泛修 → 高回归风险。可能要在 opt-report→候选 之间过滤/降权这类 issue。
3. （承接 H2）若上述仍不够，再考虑重开 bundle 设计（per-scenario 子打分 / 限定编辑范围）。

> 复现路径：`POST /api/evolve/agents/3/run?maxIter=1` → 看 `t_agent_evolve_ab_run` 的 `candidate_target_rate` vs `baseline_target_rate` + `ab_scenario_results_json` 里 subset=target 的 baseline/candidate.status 翻挂。

> **⚠️ caveat（观察 1 用了 maxIter=1）**：观察 1 只生成 1 个候选、**没有第二轮**，而 orchestrator 的「predict→reconcile→reflect」是**跨迭代纠错**机制（押预测→A/B 后对账→反哺下一轮换思路），maxIter=1 下它**没机会启动**。所以"候选负优化"是 1 个数据点，不代表机制无效——见观察 2。

### 观察 2/≥3 — 2026-06-23，run `e0606daa`（agent 3，**maxIter=3**，给跨迭代纠错公平机会）

干净跑完 3 迭代（workflow 驱动，非 agent-19 chat session）。结果**比观察 1 更复杂、也更指向 loop 编排/keep 逻辑有 bug**：

- **3 个候选都生成了**（`421389e2` / `c1312ede` / `285f6684`），**但 `t_agent_evolve_ab_run` 只有 1 条 A/B**（iter 1）→ **iter 2/3 的候选没被真正 A/B 评估**（编排异常：候选生成了但没跑对比就 `kept=false`）。
- **唯一那条 A/B 是正向的**：target **50%→75%（+25pp）、回归 0、总体 +2.04pp** —— 是个**有改进、无回归的候选**，**却仍 `kept=false`**（3 迭代全 kept=false，0 赢家）。
- **对观察 1 结论的纠偏**：候选**并非一律负优化**（本轮 iter-1 候选 +25pp 正向）。所以"0 赢家"的根**不只是候选质量**，还叠了两个编排/判定层 bug 嫌疑：
  1. **不按迭代跑 A/B**：3 候选只 1 次 A/B → reflect 闭环拿不到 iter 2/3 的真实反馈，纠错无从谈起。
  2. **keep 判定没采纳一个 +25pp/0 回归的正向候选** → decideKeep（pairwise net-wins 显著性闸 / vs-best floor）可能过严或有 bug，把真改进也判 false。

**修正后的下一步方向**（比观察 1 的"只修候选生成"更全）：
- 先查 **evolve-loop workflow 为什么 3 迭代只跑 1 次 A/B**（编排/JS 控制流 bug，最可疑）。
- 再查 **decideKeep 为什么不采纳 +25pp/0 回归 的候选**（判定闸是否过严 / 小样本 n=5 的显著性把正向也压成不显著）。
- 候选生成"最小+对靶"仍要做，但**当前更卡的是编排+判定**，应先修这两层再谈候选质量。
- （模型层）candidate-gen/orchestrator 现跑 glm-5.1，偏弱；可评估换 coding plan 上的 `doubao-seed-2.0-pro` 提升指令遵循。

### 观察 3 — 真因更正（2026-06-23 深挖，**推翻观察 1/2 的根因判断**）

挖到 e0606daa 的 per-step 数据后，真因是 **FR-C7 A/B 预算闸**，不是候选质量、也不是控制流 bug：

- iter2/3 的 `TriggerAbEval` 返回 `status: rejected, reason: "per-agent A/B budget reached (targetAgentId=3, used=30/31, cap=30)"`。
- **FR-C7 这个 cap 配置名叫 `skillforge.evolve.ab-budget-per-run`(默认 30),但实际按 agent 累计**(`FlywheelRunService.countEvolveAbTriggersForAgent`,数该 agent 所有 run 的 A/B step 行——CRIT-1 安全修复从 per-run 改成 per-agent 累计)。**agent 3 历史累计 A/B 早 ≥30 → 永久冻结**,任何新 evolve run 在 agent 3 上最多挤进 ~0-1 次 A/B 就被拒。
- **更正观察 1/2**:① 候选生成**其实是好的**(iter1/2/3 都"仅追加一条"最小对靶规则,reflect 真生效:iter2/3 明确纠 iter1 的过宽)；② "iter2/3 不跑 A/B" **不是编排 bug,是预算闸拒掉**；③ iter1 那次被放行的 A/B 是 **+25pp/0回归** 的好候选(只是 decideKeep 仍 kept=false——见下,这才是剩下值得查的）。

**真正的下一步方向(取代前述)**：
1. **agent 3 的 A/B 预算耗尽 = 当前最大 blocker**。要继续测/跑 loop:(a) **换一个预算有余量的 agent**(如 agent 1)；(b) 调高 `skillforge.evolve.ab-budget-per-run`；(c) **重审 cap 语义**——名字"per-run"但实为"per-agent 终身累计",会**永久冻结任何被反复迭代过的 agent**,可能该改成按窗口(per-run / 每日)而非终身。
2. **decideKeep 为何拒 +25pp/0回归候选**(次要,但真): iter1 有真改进却 kept=false。疑似配对 net-wins 在全 50 场景上算、5 个 target 改进被 45 回归场景稀释 → 判不显著。值得单独验(用有预算的 agent 重跑能顺带看清)。
3. 候选生成"最小对靶"**当前看是 OK 的**(具体 issue 下),不是优先级；宽泛 issue(token 效率类,见观察 1)仍可能诱发越界,低优。

### 观察 4 — 收口（2026-06-24，run `7f34d911`，FR-C7 解冻后 maxIter=3）

FR-C7 修复部署后,在 agent 3 上重跑(预算已解冻),**整条"evolve loop 有问题/0 赢家"调查闭环**:

| iter | kept | overall Δ | 候选 |
|---|---|---|---|
| **1** | ✅ **true** | **+4.08** | 仅追加一条 behavior_rule:**SubAgent 派发前先 AgentDiscovery,避免误用 agentId=3 递归自派被拒**(最小/对靶/干净) |
| 2 | false | −6.12 | 进一步收窄 SubAgent 规则 → 回归 → **正确拒** |
| 3 | false | −2.0 | WebFetch 预检规则 → 回归 → **正确拒** |

- **多轮 A/B 都放行**(iter1/2/3 各一次,不再冻结);窗口内预算 11/30(没烧爆)。
- **decideKeep 工作正常**:keep 了 +4.08 真改进、拒了 2 个回归 → **当初没改 decideKeep 是对的**。
- **结论(收口)**:整条线的真因 = **FR-C7 per-agent 终身累计冻结**(已修为滚动窗)。候选生成、reflect 闭环、判据(decideKeep)、管道收尾**都正常**;之前的"0 赢家"= 被冻 + 早期候选边际 + maxIter=1 不公平测试 的叠加假象。SkillForge 自进化产出**第一个被证实的真赢家**(候选,采纳仍人定夺)。
- 遗留(非 bug):赢家 iter1 候选(AgentDiscovery-before-SubAgent 规则)是 hill-climb best,待人 review 采纳。

## 阅读顺序

1. [prd.md](prd.md) — 目标/非目标/验收/退出标准/冒烟
2. [tech-design.md](tech-design.md) — 4 缝的 file:line + C-seam + 不变量 + 风险 + 测试
3. [mrd.md](mrd.md) — 用户诉求 + architect review verdict（意图清楚可跳）

## 关联

- 现状：[autoevolving 能力现状快照](../../../references/autoevolving-capability-stage-2026-06-17.md)
- 前身：[EVOLVE-JUDGE-GROUNDING Phase 1](../2026-06-17-EVOLVE-JUDGE-GROUNDING/index.md)
- 归属：[AUTOEVOLVING-MASTER](../2026-05-28-AUTOEVOLVING-MASTER/index.md) V3（候选质量是 outer-loop 收敛前提）
