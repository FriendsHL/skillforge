# MRD — EVOLVE-JUDGE-GROUNDING

## 用户原始诉求

> 「自进化的 loop 可以参考他们的一些使用规范或者经验，我们该如何优化？」
> （2026-06-17，对照 Claude Dynamic Workflows blog 复盘自进化路线时提出）

用户先看了 Claude Code workflow 能力，发现工作流模式跟 SkillForge 开发模式同源；进一步讨论后把方向收敛到「**自进化 loop 的判定与候选质量**」——明确指出对标对象**不是** Claude Code 的 workflow 能力（骨架已够），而是「自治自进化系统怎样才算正确」。

## 背景痛点

evolve loop 机制已端到端打通、测量层也加固（对靶恢复、infra 隔离、weightedScore、行为 oracle v2），但**实跑 0 真赢家、闭环空转**：

- run 45a25dba：「测量层修复全生效但 0 赢家；瓶颈移到候选质量（候选 grounding 到 target 场景）」
- 候选从 opt-report session issue 出，跟 agent 真实 9 个可复现失败脱节 → 不对靶
- judge 是绝对加权分（`0.6·general + 0.4·harvest`），无配对比较、无统计显著性、无 held-out 强制 gate，且是"证明候选更好"方向（self-preferential 风险）

## 参考的 blog 经验（不照抄能力，借经验）

- **比较式判断比绝对打分更可靠**（《A Harness for Every Task》sorting 用例点名 pairwise/锦标赛）
- **对抗式核验 / Popper 证伪**：每个产出由独立 agent 反驳，迭代到收敛 —— 结构上压 self-preferential bias
- **根因调查**：从互不相交证据起多假设、各自面对 verifier+refuter —— 映射"候选必须对靶具体失败"
- **何时不该用 workflow**：不是每任务都值得 —— 提醒别给小改动套重流程

## 约束 / 边界

- 不重写整个测量层；优先用**已存在但没被用上**的配对数据（`perScenarioFlips`）。
- 跟 AUTOEVOLVE-CLOSE-LOOP 阶段B（尺子敏感度）边界清晰：本包管「判定方法」，阶段B 管「尺子刻度」。
- 大改（grounding / refuter / held-out）分 Phase，不在 Phase 1 一次做完。

## 未解决问题（PRD 澄清）

- pairwise 判定的"显著性/margin"阈值取多少？（见 prd 验收 + tech-design 开放问题）
- Phase 1 是否完全替换绝对分判据，还是并存（双判据取严/取宽）？
