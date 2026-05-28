# AUTOEVOLVING-MASTER — autoEvolving 总需求包

> **创建**：2026-05-28
> **状态**：master overview draft，等 user ratify 路线后子需求各自展开
> **思想根**：[Karpathy autoresearch](https://github.com/karpathy/autoresearch)（630 行 Python + 3 文件 + MIT，53.5k stars）—— 7 抽象 + 5 哲学，详见 [`research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md`](../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md)
> **目标**：让 SkillForge 的 skill / prompt / tools / rules / agent / 框架本身按 Karpathy 哲学**自动持续进化**，整合现有 14-stage flywheel + memory + autoResearch + A/B + canary + Iron Law 等能力到一个**可观测的整体面板**

## 1. 三个核心问题（user 2026-05-28 明确）

| # | user 原话 | 解读 |
|---|---|---|
| **痛点 1** | "memory 的梳理整合" | 已 ship DREAMING V1（memory-curator + transcript dreaming + t_memory proposal gate） |
| **痛点 2** | "通过用户 session 信息 对当前使用 agent 的各种建议（创建 skill / skill 优化 / tools 优化 / prompt 优化 / 增加 rules）" | 已部分 ship（OPT-REPORT-V1 + 14-stage flywheel），缺整合面板 + outer loop |
| **痛点 3** | "使用 autoResearch 的思想 对各个功能和能力做打磨（skill / prompt / tools / rules / 当前框架）" | 新增（AUTORESEARCH-OPTIMIZATION V1 PRD 已草），需融入 candidate 信号源 |

## 2. autoEvolving 父伞结构

```
                        autoEvolving 父伞
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
   ① 进化对象              ② 信号源               ③ 编排方式
        │                     │                     │
   ┌────┴────┐         ┌──────┼──────┐        ┌────┴────┐
   skill     prompt    production    autoResearch    Agent-driven (已有)
   tools     rules     session       finding         DSL workflow (可选)
   agent     框架本身                A/B 结果        定时任务 (Quartz)
                                    rejected buffer
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              ▼
                       ④ 执行/调度
                       14-stage flywheel state machine
                       canary + AUTO_ROLLBACK
                       Iron Law 人审 gate
                       outer epoch loop (V3 + K-4)
                              │
                              ▼
                       ⑤ 可观测
                       t_llm_trace + span 3-kind
                       tengu_* events
                       autoEvolving 整体 dashboard ⭐ V1 重点
                       workflow node status viz (DSL 带来)
```

## 3. 已 ship 零件清单

| 类别 | 零件 | 状态 | 角色 |
|---|---|---|---|
| **数据层** | t_llm_trace + span 3-kind + origin partial index | ✅ | 信号源 #1 (production) |
| **数据层** | t_memory + proposal | ✅ DREAMING V1 | 信号源 #1.5 (memory) |
| **数据层** | t_flywheel_run + t_flywheel_run_step (V124+V125) | ✅ | run/step 抽象 |
| **执行** | 14-stage flywheel state machine | ✅ | 主执行链路 |
| **执行** | 4 surface 独立 A/B (skill/prompt/behaviorRule/sandbox) | ✅ | 进化对象隔离 |
| **执行** | canary + AUTO_ROLLBACK | ✅ | 执行保险 |
| **执行** | EVOLUTION_FORK + 8 SkillSource | ✅ | identity 保留 |
| **执行** | Iron Law 人审 gate | ✅ | 业内独家 |
| **进化对象** | memory consolidation (autoDream + memory-curator) | ✅ DREAMING V1 | 痛点 1 |
| **进化对象** | OPT-REPORT-V1 (session → suggestion) | ✅ | 痛点 2 部分 |
| **可观测** | dashboard 现有 page (Reports / Insights / Flywheel Runs / Memory) | ✅ | 部分 |
| **观测** | t_llm_trace dashboard (LlmTraceStore + Span viz) | ✅ | 现有 |

## 4. 还缺的零件（autoEvolving 完整落地 V1-V5）

| 缺 | 子需求包 | 优先级 |
|---|---|---|
| **autoEvolving 整体面板**（一个 page 看全：candidate 队列 / autoResearch 信号 / memory 状态 / A/B 在跑 / 历史 ratio / 早停记录 / 各信号源贡献） | **V1（本 master 包重点）** | 最高 |
| 外部信号源（autoResearch arm） | [AUTORESEARCH-OPTIMIZATION V1](../2026-05-28-AUTORESEARCH-OPTIMIZATION/) PRD 已草 | V1 / V2 |
| K-1 拆 `optimizer_program.md`（SkillDraftService 1430 行硬编码 → meta-mutable markdown） | 新子包待开 | V2 |
| K-2 `complexity_delta` 维度（A/B score `pass - α × complexity_delta`） | 新子包待开 | V2 |
| K-3 `v_experiment_ledger` view（5 列 append-only：commit / metric / complexity / status / description） | 新子包待开 | V2 |
| K-4 outer epoch loop（现 14-stage 跑到 promoted 即止，缺自动续轮 + 早停 + falsification） | 新子包待开 | V3 |
| 3 信号源融合（production + autoResearch + rejected buffer → candidate） | V3 设计 | V3 |
| Workflow DSL 编排（[Claude Code Workflow](../../../../research-docs/research/claude%20code%20源码/08%20Workflow%20工具与编排指南.md) 形式，phase/agent/parallel/pipeline/humanApprove 原语） | [OPT-LOOP-FRAMEWORK V2-DSL-RESET](../2026-05-27-OPT-LOOP-FRAMEWORK/V2-DSL-RESET.md) 已起草 | **V2（user 说前提条件）** |
| 框架自身进化（CompactionService / Engine / hook framework / .claude/rules/* → 提 PR 路径） | 新子包待开 | V4+ |
| SkillsBench 公开打榜 | AUTORESEARCH V1 PRD Phase 4 | V4 |

## 5. V1 范围（本 master 包重点）= user 第 4 点 "E"

> user 原话："E吧，毕竟能 run 起来之后 用户能看到整体的面板，然后 效果不对 能够根据面板和可观测能力 发现具体问题"

### V1 核心交付

1. **autoEvolving Dashboard Page**（新 page `/autoevolving` 或现有 dashboard 改 layout）
   - 顶部 KPI 卡片：当周 promoted / rolled_back / running 数 + autoResearch finding pending 数 + memory proposal pending 数
   - 中间 **3 信号源面板**（即使 V1 还没融合，先并列展示）：
     - 信号 ①: production session（OPT-REPORT 最近 4 周 + 当前 14-stage 各阶段 candidate 数）
     - 信号 ②: autoResearch finding（接 AUTORESEARCH-OPTIMIZATION V1 ship 后的 t_research_finding 表）
     - 信号 ③: memory proposal（接现有 t_memory_proposal）
   - 底部 **执行链路面板**：A/B test 在跑列表 + canary 状态 + 早停触发记录（V1 暂无早停，留 V3）
   - 侧边 **早期问题诊断**：用 t_llm_trace / tengu_* events 聚合最近 24h 的 anomaly
2. **整合现有能力，不引入新进化机制**：
   - 14-stage flywheel 不动
   - DREAMING V1 不动
   - OPT-REPORT-V1 不动
   - autoResearch arm 等 V2 接入（V1 dashboard 留 placeholder）
3. **可观测 first**：让 user 看到现有 autoEvolving 链路的完整状态，能据此判断"哪里效果不好"
4. **不做的事**：
   - ❌ outer loop (V3 才做)
   - ❌ K-1~K-4 (V2-V3)
   - ❌ DSL workflow runtime (V2 才做)
   - ❌ 框架自进化（V4+）
   - ❌ 改 14-stage state machine（V3 才动）

### V1 估时

- Sprint 1-2：dashboard page + 3 信号源面板（M）
- Sprint 3：执行链路面板 + 早期诊断（M）
- Sprint 4：dogfood 2 周 + 用户反馈迭代

**~3-4 周**，最早可独立 ship。

## 6. 完整路线图（V1 → V5）

| 版本        | 工作                                                                                                                                                                                                 | 子需求包     | 估时            |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------- |
| **V1** ⭐  | **autoEvolving 整合面板**（本 master 包 V1 重点 = user 第 4 点 E）                                                                                                                                             | 本包 V1 部分 | 3-4 周         |
| **V2**    | **3 个核心 + 1 个可选并行**：(a) Workflow DSL Phase 1 MVP（编排可视化 / user "前提条件"） + (b) AUTORESEARCH-OPTIMIZATION V1（外部信号源） + (c) K-1 拆 `optimizer_program.md` + (d) K-2/K-3（complexity_delta + ledger view）可选 | 子包独立     | 6-8 周         |
| **V3**    | **K-4 outer loop + 3 信号源融合**（production + autoResearch + rejected buffer → candidate）+ falsification + predicted_impact + 早停                                                                       | 新子包      | 4-6 周         |
| **V4**    | **SkillsBench 公开打榜**（86 tasks × 11 domains）+ Pareto frontier (Sentient EvoSkill) + 对外对比报告                                                                                                          | 子包       | 2-3 周         |
| **V5**    | **框架自进化**（CompactionService / Engine / hook framework / .claude/rules/* 等代码层；user 第 1 点说不需 sandbox，因为走 PR 路径）                                                                                      | 新子包      | 4-6 周         |
| **V99**   | Bilevel Autoresearch（`optimizer_program.md` 自我演化）                                                                                                                                                  | 实验性      | 待定            |
| **Total** |                                                                                                                                                                                                    |          | ~5-6 个月 V1-V5 |

## 7. Karpathy 5 哲学的 SkillForge 应用

详见 [karpathy-autoresearch-thinking wiki](../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md)。摘要：

| 哲学 | autoEvolving 应用 | V1 落地? |
|---|---|---|
| ① 人编程 meta-prompt，不写代码 | optimizer_program.md 拆出 | V2 (K-1) |
| ② Simplicity criterion（复杂度负价值）| A/B score 加 complexity_delta | V2 (K-2) |
| ③ Single mutable file（每次只改 1 surface）| 4 surface 独立 A/B（**已有**）| ✅ V0 |
| ④ Fixed budget, not fixed result | 同时段 traffic split A/B（**已有**）| ✅ V0 |
| ⑤ LOOP FOREVER，人按停 | scheduled auto-trigger，dashboard 不让 user 每轮点跑 | V3 (K-4) |

## 8. Karpathy 7 抽象的 SkillForge 应用

| 抽象 | autoEvolving 应用 | 现状 |
|---|---|---|
| ① Immutable harness | `t_eval_dataset` + production session origin partial index | ⚠️ 部分（缺 eval 物理隔离） |
| ② Single mutable artifact | 每次只改 1 个 surface | ✅ 已有 |
| ③ Meta-mutable instruction | `optimizer_program.md` 拆出 | ❌ V2 (K-1) |
| ④ Fixed budget | A/B traffic split / canary 时间窗 | ✅ 已有 |
| ⑤ Single metric | pass_rate / cost / latency 多指标，缺复合分数 | ⚠️ V2+ |
| ⑥ Append-only log | t_llm_trace / t_skill_proposal 都是 append-only，但缺 5 列简单视图 | ❌ V2 (K-3) |
| ⑦ Status enum + git | 14-stage 复杂 state machine（反方向）+ git 不自动 | ⚠️ V3 (K-4) |

## 9. 跟相邻需求关系

### 子需求包（master 包 governance）

- **[OPT-LOOP-FRAMEWORK V1 + V2-DSL-RESET](../2026-05-27-OPT-LOOP-FRAMEWORK/)** — workflow 编排能力（DSL）
  - Sprint 1+4 已 ship（schema + dashboard run history）
  - Sprint 2 framework 已 rollback（commit `cf95dd7`，dead code 0 callers）
  - V2 DSL 方向已起草 [`V2-DSL-RESET.md`](../2026-05-27-OPT-LOOP-FRAMEWORK/V2-DSL-RESET.md)
  - **作为 master V2 的 (a) Workflow DSL Phase 1 子需求**
- **[AUTORESEARCH-OPTIMIZATION V1](../2026-05-28-AUTORESEARCH-OPTIMIZATION/)** — 外部信号源
  - PRD 已草，5 D 待 ratify
  - **作为 master V2 的 (b) 子需求**
- **DREAMING-MEMORY-EXTENSION** — memory 进化
  - 已 ship 归档 `archive/2026-05-28-DREAMING-MEMORY-EXTENSION/`
  - **作为 master V0 已落地零件**（autoEvolving 痛点 1）
- **OPT-REPORT-V1** — session → suggestion
  - 已 ship
  - **作为 master V0 已落地零件**（autoEvolving 痛点 2 部分）

### V2 子需求依赖图

```
V1 master dashboard (本包)
   ↓
   ├─ V2 (a) Workflow DSL Phase 1  ←─┐
   │   (user "前提条件")              │ 串行 / 可并行待定
   ├─ V2 (b) AUTORESEARCH V1       ←─┤
   │                                  │
   └─ V2 (c) K-1 optimizer_program.md ←┘
   ↓
V3 K-4 outer loop + 3 信号源融合
   ↓
V4 SkillsBench 打榜
   ↓
V5 框架自进化
```

## 10. ratify 决策（待 user 确认）

| # | 决策 | 默认提案 |
|---|---|---|
| **M1** | 起独立 master 需求包还是合并到现有包 | **独立 master 包**（本包），子需求独立 ship |
| **M2** | V1 dashboard page 命名 | `/autoevolving` 或 `/dashboard`（现有 Reports/Insights/Flywheel Runs 整合）— user 选 |
| **M3** | V1 是否必须等 V2 (b) AUTORESEARCH ship 才能上线 | **否**，V1 dashboard 留 autoResearch panel placeholder，AUTORESEARCH V1 后接入 |
| **M4** | V2 (a) Workflow DSL 跟 (b) AUTORESEARCH 顺序 | **并行**（不冲突，独立 sprint 推进）；如 token / 人力紧张 → 先 (b) AUTORESEARCH（独立 ROI 更明确） |
| **M5** | V1 dashboard 是否含"用户主动触发 candidate 生成"按钮 | **否**，V1 纯观测（LOOP FOREVER 哲学）；手动触发留 admin endpoint |
| **M6** | 框架自进化 V5 是否真做 | **暂记 V5**，V4 完成后看 ROI 再定；user 已 ratify 不需 sandbox（走 PR 路径） |
| **M7** | DSL workflow 在 V1 dashboard 中的角色 | V1 不依赖 DSL；V2 (a) ship 后 dashboard 加 DSL workflow viz panel |

## 11. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| V1 dashboard 只是"现有能力拼接"，user 觉得没新价值 | 高 | dashboard 必须能"发现具体问题"（user 第 4 点），所以异常诊断面板是关键，不能只是 KPI 卡 |
| 多子需求并行 V2 工程量爆炸 | 中 | M4 决策：人力紧 → 先 AUTORESEARCH V1；DSL workflow 推 V3 |
| K-1 拆 `optimizer_program.md` 牵动 SkillDraftService 1430 行 | 中 | 切分小步：先抽 1 个 surface 策略到 markdown，验证可行再扩 |
| autoEvolving dashboard 跟现有 Flywheel Runs / Insights / Reports tab 数据源重叠困惑 | 中 | dashboard 改 layout：autoEvolving 作"总览"页，原 page 作 deep-dive |
| 框架自进化 V5 失控（agent 自动改 AgentLoopEngine 引入 bug） | 高 | user 已确认走 PR 路径（不 hot-reload），PR review 是天然 gate；Iron Law + 单测覆盖红线 |
| V1 dashboard 的 autoResearch panel 在 AUTORESEARCH V1 未 ship 时显空 | 低 | placeholder + "AUTORESEARCH-OPTIMIZATION V1 to ship in V2" 提示 |

## 12. Iron Law check

- V1 dashboard 是 **read-only viewing layer**，不改任何执行链路
- 不动 14-stage state machine
- 不动 4 surface A/B 流程
- 不动 Iron Law 人审 gate
- 不动 OPT-REPORT / DREAMING / canary / AUTO_ROLLBACK
- 仅新加 1 dashboard page + 数据聚合 endpoint（read-only）

## 13. 开工前 ratify 流程

1. user review 本 index.md
2. 拍板 M1-M7 决策
3. **优先 V1 还是 V2**（user 第 4 点说 "DSL 也是前提条件" → 可能优先 V2 (a)；但 "能 run 起来" → 优先 V1 dashboard 整合现有；2 选 1）
4. 选定后起对应子需求包的 prd / tech-design
5. 走 Full pipeline

## 14. 文档清单（本 master 包）

- `index.md`（本文）— master overview
- 后续待 user ratify 路线后写：
  - `roadmap.md`（V1-V5 详细 sprint 划分 + 依赖图）
  - `dashboard-mockup.md`（V1 dashboard wireframe）
  - `signals-fusion.md`（V3 3 信号源融合设计）
  - `surface-self-evolution.md`（V5 框架自进化设计）

子需求包各自独立目录（不在本包内嵌套），见 §9 链接。
