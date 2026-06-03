# prd — AUTOEVOLVE-CLOSE-LOOP

> 状态：draft，待用户 ratify。

## 1. 背景 / 痛点

evolve V1（agent 整体爬坡 Phase 1-4）机制跑通了，但本会话调查暴露三个让它"看着能跑、实际没用"的缺口：
1. **跑出赢家也落不了地**（无采纳流程）；
2. **提升不一定真/对靶**（候选 grounding 在主观 session issue、跟可复现 eval 失败脱节、report 太固定、无重复加权）；
3. **没法回答"到底有没有效果"**（无 benchmark north-star）。

## 2. 目标

把 evolve 从"会跑的机制"做成"**对靶 + 可信 + 能落地 + 可度量**"的闭环：跑多轮 → 列真实有效提升 → 一键采纳改 agent → benchmark 验证效果。

## 3. 范围

### P1 — 闭环采纳（in）
- 后端：`adopt` 端点，输入 evolveRunId + 选中赢家 bundle（或单面），**各面分别 promote**（prompt 版本激活 / behavior_rule 版本激活 / skill draft → approveDraft 落盘），原子性尽力（全成或可回滚），人触发走守卫。
- 前端：evolve 面板展示**赢家 bundle + 各面 diff**（改了啥）+ **Approve & Adopt 按钮** + 二次确认。
- **out**：自动采纳（始终人决策，对齐 Claude Code `/insights` "建议而非改"）。

### P2 — 对靶改进（in）
- **候选 grounding 接可复现失败场景**：issue 携带 failing eval scenario ids（或 top session 失败转可复现场景）；"issue→候选→A/B" 闭合到同一套用例。
- **predicted_impact**：候选产出预测"哪些场景 fail→pass / 风险 pass→fail"；下一轮拿真实翻转对账（反思机制扩展）。
- **trace re-hydrate**：出候选时把失败场景/trace 证据喂回 LLM（不只一行 suggestion）。
- **report 丰富 + MULTIPLE TIMES**：annotation/issue 加更丰富 typed facets；跨 session/场景**重复加权**（反复出现 = 高置信 = 优先），而非一次性噪声。
- **out**：6 维 55 enum 全套照搬（按我们数据量裁剪）。

### P3 — benchmark 验证（in）
- benchmark north-star：固定 held-out 场景集（现有 36 + 可扩）上的 agent 质量分，**采纳前后 + 跨时间趋势**。
- **infra 失败摘出**：`surface=other`（凭证/429/超时）失败不计入 agent 质量分母（治污染 + 留 actionable 名额）。
- **out**：引入外部新 benchmark 数据集（先用现有 + dogfood 扩）。

## 4. 验收点

- AC-1（P1）：evolve 跑完，dashboard 看到赢家 bundle + 各面 diff；点 Approve & Adopt → agent 配置真被改（prompt/rule active 版本切换、skill 落盘），人不点则不改。
- AC-2（P1）：采纳走守卫（权限 + 二次确认）；部分面 promote 失败有清晰反馈 + 不留半采纳脏态。
- AC-3（P2）：一个候选由"某 failing eval 场景"驱动生成，A/B 在**同一场景**上量提升；候选带 predicted_impact，下一轮对账真实翻转。
- AC-4（P2）：report 的 issue 带重复加权（reproducible/recurring 优先于一次性）；出候选 LLM 看到失败证据不只一行 suggestion。
- AC-5（P3）：能看 agent 在 benchmark 上的分**随采纳累积涨没涨**；infra 失败不进质量分母。
- AC-6：各期 mvn 全绿 + 关键路径活体验证。

## 5. 待 ratify 决策

| # | 决策点 | 候选 |
|---|---|---|
| D1 | 采纳原子性 | 全成或全回滚 vs 尽力 + 逐面状态反馈（推荐后者，各面 promote 本就独立守卫）|
| D2 | failing-scenario grounding 来源 | 直接用 eval 失败场景 / 把 top session 失败转可复现场景 / 两者 |
| D3 | report facets 丰富度 | 按我们数据量裁剪几维（不照搬 55）|
| D4 | benchmark 范围 | 先用现有 36 场景当 north-star，dogfood 增量扩 |
| D5 | 分期顺序 | P1(闭环)先 → P2(对靶) → P3(benchmark)，还是 P2 先（先让提升真再给采纳按钮）|
