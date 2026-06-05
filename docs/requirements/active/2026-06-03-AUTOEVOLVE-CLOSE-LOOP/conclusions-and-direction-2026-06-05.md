# AUTOEVOLVE 自进化体系 — 阶段结论与架构方向（2026-06-05）

> 一晚 live 验证 + 提速 + opt-report 对账 + 架构讨论后的整体结论。**待用户 review**。
> 关联：[postmortem-2026-06-05-live-verification.md](postmortem-2026-06-05-live-verification.md)（排查过程）。

## 0. 一句话

自进化闭环的"管路"已端到端打通并 live 实测；但**当前用单个 agent 驱动整套编排，不确定性是最大风险源**。结论倾向：**编排改成确定性工作流，LLM 只做叶子节点（候选生成 / 判断），辅以临时 subagent（指定 system prompt）**。

---

## 1. 当前体系状态（已验证 / 未验证，诚实区分）

| 项 | 状态 | 证据 |
|---|---|---|
| 端到端闭环 opt-report→候选→A/B→对账→落账 | ✅ live 实跑 | run 5eb9c71e / 28cfb26b / fc6350ea |
| G3「改配置 + 预期结果」(prediction + reconciliation) | ✅ **已生效** | 每个 iter 带 prediction+reconciliation；5eb9c71e confidence=1.0（预测 2/2 命中翻转 + 风险命中）|
| 多轮 carry-forward — reject 路径 | ✅ 已验 | fc6350ea 2 轮 completed，iter1 拒绝后 iter2 baseline 守住原值 |
| 多轮 carry-forward — winner-advance 路径 | ⚠️ **未验** | 验证 run 未出 winner，"赢家推进下一轮 baseline"路径没跑到 |
| 候选质量 | ⚠️ 波动大 | 有的 run 72→78 出 winner，有的 0/2。**机制对 ≠ 每轮有收益** |

---

## 2. 今晚的实证发现

### 2.1 迭代提速（已 commit `94a17cf1`）
- **25.6 → 18.8min/轮（-27%）**。opt-report 12.3→7.4min（aggregator glm-5.1→doubao-seed-2.0-pro）+ A/B 11.4→8.7min（并发 6→10，0 真 429）。
- **A/B 真瓶颈 = 最慢单场景的 agent loop 长度，不是并发**。拉并发收益递减（验证 run A/B 摊上慢场景拖到 ~16min）。

### 2.2 max_loops 修复
- 之前撞 maxLoops=25（orchestrator 把轮次烧在轮询上）→ 0~1.5 轮。**真瓶颈是 maxLoops 不是 duration**。
- `max_loops=70`（live）解锁多轮，验证 run completed 2 轮。
- 副作用洞察：轮询不仅烧轮次，**每个轮询轮次重发 32K context = 烧 token**。

### 2.3 opt-report 是 config-盲的（重要发现，来自手工报告对账）
对 Design Agent 跑系统 opt-report 与手工归因对账：
- opt-report 的 **session 失败归因很强**（5 个具体失败模式，2 个比手工更细）。
- 但**对 config 内部矛盾全盲**：手工抓的"prompt 自相矛盾""规格三处冲突"它一个没抓到。
- **致命点**：它的药方全是"加 behavior_rule"，且多条规则 **prompt 里已存在**（grep-first / pwd-before-git / 预览验证 / 理解需求）→ 真问题是"**规则被无视**"而非"缺规则" → 一轮轮加 → **prompt 臃肿 + 自相矛盾累积**（手工抓的那个矛盾很可能就是历史 evolve 加规则攒出来的）。
- **改进方向**：opt-report 加 **config-aware 前置检查**：① 等价规则是否已存在（在=换药不是重复加）；② 新规则与现有 prompt 有无冲突。

---

## 3. 架构方向结论（核心，待用户拍板）

### 3.1 问题：agent 驱动编排 = 不确定性过高
今晚**所有**故障都是编排-LLM 的故障，不是业务逻辑的：
- tool_ids 漂移 → 物理上调不出工具 → 20× 空转
- maxLoops 卡死
- 轮询烧轮次 + token
- "reasoning 说调 A，tool_use 却发 B"（LLM 选工具不可靠）

### 3.2 关键认知：步骤本就是确定性的，只有编排是 LLM
opt-report（固定 workflow）/ GenerateCandidate（service）/ A/B 打分（沙箱+oracle）/ ReconcilePrediction（算法）/ keep-reject（双标准阈值）—— **全是确定性服务**。唯一 LLM 驱动的是**最外层编排**（orchestrator agent），而它是**全部脆弱性的来源**。

### 3.3 结论：确定性工作流编排 + LLM 叶子节点
- **编排**（循环 / 排序 / 轮询 / 阈值判断）= 写死的代码。无 maxLoops、无漂移、无轮询浪费、行为可复现、可断点续跑。
- **LLM 只在真需判断的叶子**：候选生成（创造力刚需）、必要时的判断节点。
- **不需要多个常驻 agent**；用**临时 subagent**（运行时指定 system prompt + tools）当节点即可。
- 用户洞察认同：**如果一开始就是"固定工作流 + agent/tool 节点"，这套早落地了**——用 LLM 当 orchestrator，是在用它的不确定性换一份这套流程根本不需要的灵活性。
- 适用边界：只有当**进化策略本身要自适应新情况**时，agent 驱动才值钱；本流程已进入稳定期 → 确定性赢。

### 3.4 前提（落地依赖）
把 `GenerateCandidate / TriggerAbEval / GetAbResult` 从 **agent tool 开成 REST 端点** → 确定性编排层（CC Workflow 或 in-product JS/Java 循环）才能驱动细粒度循环。当前 EvolveController 只暴露粗粒度 `POST /run`（内部还是 spawn orchestrator agent → 回到 maxLoops）。

---

## 4. 产物文件化（新方向）

每轮 evolve 把产物落成**文件**，供展示 / 复盘 / 追溯（而非只埋在 DB JSON）：
- iter1：opt-report 报告 + 归因调整
- iter2：优化方向 + 候选 diff
- 全局：分数轨迹、prediction-vs-actual 对账可视化
- 价值：人类复盘可视化、artifact trail 可追溯、脱离"读 DB JSON 才能看进展"。

---

## 5. 合并 backlog / 下一步

- [ ] **V148 持久化** `max_loops` + `max_duration_seconds`（现仅 live DB，rebuild 会丢）
- [ ] **B 优化**：GetAbResult / GetOptReport 阻塞超时 90s→~300s（降轮次 + 降 token）
- [ ] **opt-report config-aware 去重 / 矛盾检查**（§2.3）
- [ ] 验证 **winner-advance** carry-forward 路径（需一轮真出 winner）
- [ ] **候选质量**（run-to-run 波动）—— 单独课题
- [ ] **架构重构设计**（建议起 architect agent）：确定性编排层 + REST 细粒度端点 + 叶子 subagent + 产物文件化
- [ ] **Design Agent 试卷搭建**：11 个 harvested → `1-mixed` + 设计专属 benchmark（GAIA/τ-bench 不衡量 UI/UX）才能真自进化它
- [ ] **环境治理**：每小时 watcher + pgdata 锁竞争（今晚又踩，手动重启撞锁）

---

## 6. 诚实边界

- 提速、max_loops、G3 是**实测验证**的；架构方向是**基于今晚证据的工程判断**，非 benchmark 结论。
- opt-report 的错题本本职**做得好**，config-盲是**具体可修的盲点**，不是它没用。
- "管路通了" ≠ "每轮真提分"——候选质量是下一个真问题。
</content>
