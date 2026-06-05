# Postmortem — BC-M2b live 端到端验证（2026-06-04 夜 ~ 2026-06-05 凌晨）

> 一句话：为验证 BC-M2b（让 evolve 真转）真跑了一晚，**核心收获是修掉一个我自己 BC-M2b 子轮2 引入的真 regression（orchestrator tool_ids 漏了 GetOptReport）**；但**排查过程我绕了大远路**——guess-and-check 试错了一长串才回头读 transcript 看出真因。本文记录问题链、根因、**方法论教训**、预防措施、遗留。

---

## 1. 背景与目标

- BC-M2b 子轮1/2/3 代码已完成并对抗评审通过（reviewer 全 PASS）。子轮3 已 commit。
- 用户问："服务重启了吗？执行过一次吗？" → 暴露真实 gap：**所有验证都是测试级 + 评审级，从没 live 真跑过一次 evolve**。
- 目标：真起服务 + 驱动一轮 evolve（≥3 迭代），看"还欠缺什么"。

---

## 2. 问题链（按我实际撞到的顺序 —— 含弯路）

| # | 现象 | 我的处置 | 是否真因 / 是否 BC-M2b |
|---|---|---|---|
| 1 | opt-report `RecordBatchAnnotationsTool` 撞 `t_flywheel_run_step.id` varchar(36)（弱模型给的 batchId 超长）→ opt-report 崩 | 修：`normalizeStepId`（≤36 透传，>36 派生稳定 UUID）+ 单测 | 真 bug（非 BC-M2b，opt-report 老基础设施）✅ 已修 commit |
| 2 | `max_tokens continuation failed`（glm-5.1 推理模型 + maxTokens=8192 太紧，截断且 content 空、续写接不上）→ run 死 | "修"：maxTokens 8192→32768 | **部分是症状**（真因 #6 导致上下文膨胀加剧了它）；调大本身合理 |
| 3 | ark 5 小时用量配额耗尽（多轮 opt-report fan-out = 百万级 input token；4 次 run）→ HTTP 429 | 把 evolve agents SQL 切到 xiaomi-mimo | 外部配额，非代码 bug |
| 4 | 怀疑 mimo 限流 | 查证：mimo 25 调用 0 失败，**没限流** | 排除 |
| 5 | **orchestrator 反复狂调 RunWorkflow ×19~22、进不了循环、0 迭代** | ❌ 走弯路：试 ② prompt 铁律（无效）→ ① 改 RunWorkflow 返回消息（无效）→ 退回 V140 prompt（仍无效）→ 一度误判"模型能力不行" | **症状**，真因见 #6 |
| 6 | **【真因】读 orchestrator session transcript（d5473b52）发现**：模型 reasoning 文本一直说"用 GetOptReport"，但每次 tool_use 都发 RunWorkflow —— 因为 **GetOptReport 不在它的 tool_ids 里！** | 修：V146 把 GetOptReport 加回 tool_ids（列 + config JSON）+ regression 守卫测试 | **真因 = 我 BC-M2b 子轮2 的 V144 重写 tool_ids 加 ListActiveHarvestedScenarios 时静默漏掉了 GetOptReport**；V145 照抄继续丢 ✅ 已修 commit |
| 7 | 修 #6 后进了循环，但 GenerateCandidate 撞 ark 429（它走 default-provider=ark，不读 agent model_id）| 改 `default-provider` ark→xiaomi-mimo（application.yml）+ rebuild + 重启 | 配置；✅ 已 commit |
| 8 | 全链路跑通（RunWorkflow×1→GetOptReport→GenerateCandidate→TriggerAbEval→GetAbResult），但 0 迭代，`Agent loop completed`（正常结束非崩）：`Duration limit exceeded (654s)` | 诊断：`AgentLoopEngine:626` `max_duration_seconds` 默认 600s；A/B（50 场景×mimo）太慢没跑完。设 1200s（20min，live） | 调参 + **A/B 太慢是更深的根**；未持久化 |

---

## 3. 真正的根因（#6）+ 为什么没早发现

**根因**：`V144__evolve_orchestrator_harvested_target.sql` 重写 `evolve-orchestrator` 的 `tool_ids` 时，为加 `ListActiveHarvestedScenarios`，**整列重写却漏抄了 `GetOptReport`**（列 + config JSON 两处都漏）。V145 照抄 V144 的列表继续丢。

**后果链**：prompt（一直）让 orchestrator 调 `GetOptReport` 读归因报告 → 但该工具不在 allowlist → 模型**物理上调不出来** → reasoning 说"用 GetOptReport"、tool_use 却退而求其次发 `RunWorkflow` → 被 "already running" 挡 → 死循环 ~20 次 → loop 预算耗尽 → run 卡在 step 1、0 迭代。glm 和 mimo **都一样**（证明非模型、非 prompt 文案问题，是缺工具）。

**为什么"之前能跑、现在不能"**：V144 之前 GetOptReport 在 tool_ids 里。用户从第一句就说"是我们改动搞坏的 / 怀疑 prompt"——**方向完全正确**，只是具体机制是 tool_ids 不是 prompt 文案。

**transcript 是 5 分钟就能看出的铁证**（assistant 文本 vs tool_use 不一致 = 工具缺）。我却到很晚才去读它。

---

## 4. 方法论复盘（本次最该记住的）

### 教训 1：先读真实证据（transcript / diff），别在多变量环境里 guess-and-check
真因可由 transcript 直接看出。我却先后试了 **maxTokens → prompt 铁律 → 工具返回消息 → 换 provider → 退 V140 prompt** 五个修复/实验，全是治症状或瞎试。这正是 [`systematic-debugging.md`](../../../.claude/rules/systematic-debugging.md) 的 **Iron Law（NO FIXES WITHOUT ROOT CAUSE FIRST）** 和 **3-Fix Architecture Rule（≥3 次修复失败要停下质疑）** 反例。
> 正确做法：orchestrator 行为异常 → **第一时间 dump 它的 session transcript 看它实际调了什么、说了什么**，而不是改配置试。

### 教训 2：信用户"之前能跑→看我们改了啥"的逻辑
用户反复说"之前 glm/mimo 都没这问题、是我们改的"。这是最强的定位线索（变量收敛到"我们的 diff"）。我当时反而用"模型弱"搪塞过去。**"曾经可用的功能现在坏了"= 看最近改动的 diff，几乎总比"猜模型/环境"快。**

### 教训 3：我的 V140 实验本身是脏的（一次改太多变量）
我退回 V140 prompt 想证伪"是 prompt"，但**只退了 prompt、没退 tool_ids**（在另一列），且 server 代码/模型/数据全变了。结果 V140 也失败，我差点据此误判。**做对照实验要一次只动一个变量**；当时该意识到环境太脏、无法干净对照，就该转去读 transcript。

### 教训 4：整列重写配置 = 高危
V144 用"重写整个 tool_ids 列表"来加一个工具，漏抄是必然风险（跟"整段重抄 prompt"一类）。**应该是"在现有基础上加一项"而非"整列替换"**，或有测试守卫。

---

## 5. 技术发现清单（状态）

| 项 | 状态 | 持久化 |
|---|---|---|
| Gap A：opt-report varchar(36)（RecordBatchAnnotations）| ✅ 修 + 单测 + live 验证 | commit `cab85aaf` |
| ①：RunWorkflow 返回消息自解释 | ✅ 修 | commit `cab85aaf` |
| **GetOptReport tool_ids regression（核心）** | ✅ 修（V146）+ regression 守卫测试 + live 验证（RunWorkflow×1→GetOptReport→进循环）| commit `cab85aaf` |
| maxTokens 8192→32768 | ✅（V146 config 含）| commit `cab85aaf`（V146）|
| default-provider ark→xiaomi-mimo | ✅ | commit `c4dbe9b0` |
| `max_duration_seconds` 1200（20min）| ⚠️ **仅 live DB，未持久化** | 需 V147 |
| evolve agents → xiaomi-mimo（13 系统 agent + Main Assistant）| ⚠️ **仅 live DB** | 用户 infra 决定，未 migration |

---

## 6. 当前结论（诚实，不夸大）

- **evolve 驱动通了**：全链路（RunWorkflow→GetOptReport→GenerateCandidate→TriggerAbEval→GetAbResult）live 真执行，A/B 在 50 场景上真跑，无配额/截断/死循环。
- **但"完整一轮 + 出赢家"还没真正验证到**：卡在 A/B 在 orchestrator 时长预算内跑不完（A/B 太慢）。
- BC-M2b 接线逻辑**自始至终没出过错**；所有 live 故障都是运行时/上游/配置/我引入的 tool_ids regression。

---

## 7. 遗留 / 下一步

1. **A/B 提速（最后一公里的真瓶颈）**：50 场景 × 两臂 × 多轮 × mimo 推理太慢。考虑：A/B 只跑 target 子集 + 少量 benchmark；沙箱 agent 用更快的非推理模型；或 orchestrator 不同步死等。
2. **持久化 live 配置**：`max_duration_seconds`（+ 复核 maxTokens/tool_ids 是否都进了 V146）做成 **V147** migration；agent→xiaomi 的切换看是否要固化。
3. **验证完整一轮**：A/B 提速或时长够后，跑 maxIter=1 先证一整轮（含 ReconcilePrediction + RecordIteration）能完成、迭代落账。
4. **环境治理**：本机有"每小时尝试重启 server"的 watcher（端口冲突时失败），多实例 + pgdata 锁竞争给重启添乱——值得查掉。
5. BC-M2b 收尾归档（delivery-index / 需求包 active→archive / todo）等"完整一轮"真验证后再做。

---

## 8. 预防措施

- ✅ **已加**：`OrchestratorPromptDriftGuardTest.everyPromptInvokedToolIsInLatestToolIds` —— "prompt 里 `Name(` 调用的每个工具都必须在 tool_ids 里"。这条 CI 不变量**本可第一时间挡住 GetOptReport regression**。
- 建议：凡"整列/整段重写"配置的 migration（tool_ids / system_prompt），都配一条"= 上一版 + 已知 delta"或"不变量"守卫（drift guard 已对 prompt 做了，tool_ids 现在也有了）。
- 建议（给 systematic-debugging.md 补一条）：**agent 行为异常时，第一步 dump session transcript（assistant 文本 vs tool_use）**，列入"多组件取证模板"。
</content>
