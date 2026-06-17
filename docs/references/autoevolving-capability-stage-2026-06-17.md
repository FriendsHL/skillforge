# SkillForge 自进化（autoEvolving）能力现状 — 2026-06-17 快照

> 类型：dated snapshot（会随交付推进过期）。**交付事实唯一来源仍是 [delivery-index.md](../delivery-index.md)**；本文是整合视图，方便一眼看清"自进化这套东西现在到哪一步"。
> 触发：2026-06-17 对照 Anthropic《Dynamic Workflows》两篇 blog 复盘自进化路线时整理。

## 一句话状态

**机制已端到端打通**（DSL 引擎 → A/B+oracle 测量 → agent 级候选包 → 爬坡 loop → dashboard + 一键采纳），测量层也已加固（对靶、infra 隔离、weightedScore、行为 oracle v2）。**但 loop 当前产出 0 真赢家、空转**——瓶颈已从"测量"移到 **候选 grounding 到真实目标失败 + 尺子敏感度**，且尚无自动外层 epoch loop（V3）、无 benchmark north-star（P3）。

## A. 能力分层 — 已交付的部分

| 层 | 状态 | 交付物 / commit |
|---|---|---|
| **L1 Workflow DSL 引擎** | ✅ | AUTOEVOLVING-V1-DSL-DASHBOARD（Sprint1-4，2026-05-29，`9000bd5`/`b675ee7`/`85ff279`）：Rhino + L1 沙箱 + 6 原语（agent/parallel/pipeline/phase/log）+ 独家 `humanApprove` + journal-replay resume；`.workflow.js` 免重启热加载；持久化 `t_flywheel_run(_step)`（V124/V125） |
| **L2 信号/测量层** | ✅(主) / 🟡(尺子) | EVAL-V2 + EVAL-DATASET-LAYER（V109-V113，30 benchmark 机器判分 oracle）/ agent-role-aware dataset（V117）/ 4 surface A/B（skill/prompt/behavior_rule/sandbox）/ behavior oracle v2（BC-M1/BC-M2a，可复现 badcase harvest）/ infra 失败摘出分母（EVAL-429）。**held-out/blind-oracle 目前是流程纪律不是系统特性；无 A/B 统计显著性检验** |
| **L3 候选生成** | ✅ | per-surface 候选 + **agent 级 bundle（路 B，2026-06-02）**：候选=跨面改动包、整-agent 一个分、best=各面版本指针元组；反思回流（AGENT-FLYWHEEL，每轮 delta+理由喂下一轮 + judge per-case rationale） |
| **L4 外层 loop / 编排** | ✅(单形态) / ❌(自动 epoch) | OPT-REPORT-V1（生产 session 信号 fan-out 报告）/ **hill-climb evolve loop（EVOLVE-LOOP-HILLCLIMB 阶段A，V151）** / engine fix（F1-F7：对靶恢复 + 测量守卫 + 阈值改革）/ close-loop 一键采纳（P1，人工 gate）。**K-4 自动外层 epoch loop 未做（V3）** |
| **L5 Dashboard / 可观测** | ✅ | `/autoevolving` 页（KPI + 3 信号源面板 + workflow DAG）/ flywheel 可视（VISUAL-STATUS / FLOWCHART / PER-RUN）/ evolve 轨迹面板 + 候选改动 diff（P2a SemanticDeltaPanel，今日合入） |

## B. 当前瓶颈（引用证据）

- **0 赢家 / 瓶颈上移**：run 45a25dba —「测量层修复全生效（target rate 真值/badcase 在场）但 0 赢家；瓶颈移到候选质量（候选 grounding 到 target 场景）」
- **候选不对靶**：AUTOEVOLVE-CLOSE-LOOP index —「agent 3 有 9 个可复现真实失败，但 evolve 从 opt-report session issue 出候选（跟那 9 个失败脱节）、还拿无关固定场景当尺子 → 候选不对靶」
- **闭环空转**：P1 adoption —「闭环基础设施就位（赢家→diff→一键采纳→agent 真改），但现在空转（没真赢家可采）」
- **尺子爬不动**：HILLCLIMB 阶段 B（EVOLVE-BADCASE-SENSITIVITY）是「让加权分真爬得动」的下一步

## C. Judge / 测量当前怎么判赢家

- **绝对加权分（非统计显著性）**：`weightedScore = 0.6·generalPassRate + 0.4·harvestPassRate`（缺 harvest 子集则退化为纯 general），读时在 `GetAbResultTool` 算。
- `decideKeep` 用 weightedScore 为主判据；`wouldPromote` 降级为 advisory。
- 两道额外闸：F3 `minMeasuredN`（样本不足→inconclusive 不 keep）、F6 vs-original 锚（候选 general ≥ 原始 −5pp）。
- 3 停止条件：达 `targetWeightedScore` / 收敛 `noImproveStreakLimit` / `maxIter`（固定计数）。
- surface 级双判据仍在：behavior_rule `target≥+10pp 且 regression≥−3pp`；prompt/skill 阈值已从 15pp 改 5pp（n=18~36 下 15pp 结构性够不到）。
- **无 pairwise/锦标赛对比；无 held-out 强制 gate；judge 是"证明更好"方向（有自我偏好风险）**。

## D. 明确未做 / 暂缓

- **EVOLVE-BADCASE-SENSITIVITY（CLOSE-LOOP 阶段B）**：尺子敏感度，待用户拍升 active
- **CLOSE-LOOP G3（predicted_impact）/ P3（benchmark north-star）**：未做
- backlog：OUTCOMES-RUBRIC-FOUNDATION、EVAL-DYNAMIC-USER-SIM、AUTORESEARCH-OPTIMIZATION（V2）
- AUTOEVOLVING-MASTER V2-V5 全未启动

## E. AUTOEVOLVING-MASTER 路线（V0→V5）

- **V0 ✅** schema + OPT-REPORT + DREAMING V1 + 14-stage flywheel + canary + Iron Law
- **V1 ✅** DSL 引擎 + `/autoevolving` dashboard + OPT-REPORT 转 workflow
- **V2**（未启动）autoResearch V1 + K-1 拆 `optimizer_program.md` + **DSL Phase 2 原语（full humanApprove + workflow 嵌套 + 自适应 budget + 并发 pipeline）** + K-2/K-3
- **V3**（未启动）**K-4 外层 epoch loop** + 3 信号源融合 + **falsification** + predicted_impact + early-stop
- **V4**（未启动）SkillsBench 公开 benchmark + Pareto frontier
- **V5**（未启动）框架自进化（CompactionService/Engine/hook/`.claude/rules`，走 PR 路径）

## F. 由本次 blog 复盘新立的两个方向（backlog）

1. **[WF-CONCURRENT-PIPELINE](../requirements/backlog/WF-CONCURRENT-PIPELINE/index.md)** — 学 CC 的并发 pipeline（stage 间重叠、无 barrier）补强 L1 引擎（现 `pipeline()` V1 串行）。归属 V2(d) DSL Phase 2。
2. **[EVOLVE-JUDGE-GROUNDING](../requirements/backlog/EVOLVE-JUDGE-GROUNDING/index.md)** — 参考 CC 的对抗证伪 + pairwise/锦标赛判定经验，治 L2/L3 的"绝对打分 + 候选不对靶"瓶颈。跟 CLOSE-LOOP 阶段B + V3 falsification 合流。
