# GOAL-ALIGNED-AGENT-FACTORY MRD

---
id: GOAL-ALIGNED-AGENT-FACTORY
status: mrd-complete
source: user
created: 2026-08-26
updated: 2026-08-26
---

## 用户诉求

用户希望 SkillForge 首先是一个统一的个人助手，而不是要求用户在多个垂直 Agent 之间切换。这个助手能够完成各种端到端任务：写代码并把完整需求做到上线发布、写小说、生成视频，以及未来尚未预定义的任务。面对不同目标时，助手可以在后台组装专业能力或调用专业子 Agent；缺少能力时，能够从 GitHub、SkillHub、MCP 或其他可信来源检索候选，自行创建缺失的 Skill 或 Tool，验证后纳入个人助手，并在后续使用和 Benchmark 中持续优化。

用户同时明确提出核心担忧：如果能力拆解、工具选择、Skill 创建、评测指标和晋级都由 Agent 或模型自己决定，最终效果不一定与用户真正目标一致。系统不能因为某个内部评分提高，就宣称个人助手变得更好。

## 背景

SkillForge 已具备 Agent 配置、Skill 导入/创建/进化、Tool/MCP、Workflow/SubAgent、Eval、整 Agent Bundle A/B、人工采纳、Canary、Rollback、AutoResearch、媒体运行时和 Artifact 等基础能力。

当前缺口不是单个 Tool，而是缺少把这些能力连接成一个受治理产品闭环的上层模型：

1. 没有稳定表达用户目标、反目标、验收样例和决策权的 Goal Contract。
2. 没有统一描述 Prompt、Skill、Tool、MCP、Workflow、Media 等能力的 Manifest。
3. 没有可复现的 Agent Blueprint revision 和 resolved lockfile。
4. 没有统一的能力缺口分析与本地/外部发现流程。
5. 外部代码的来源、许可、权限、隔离、验证和安装晋级缺少统一门禁。
6. 现有自进化候选主要覆盖 Prompt、Behavior Rule 和 Skill，尚未覆盖完整 Blueprint。
7. Eval 指标可能由模型自我定义，存在代理指标与用户真实价值偏离的问题。

## 期望结果

用户始终通过同一个个人助手发起任务。产品行为是“助手理解目标、后台组织能力、证据化验证”，而不是要求用户先成为 Agent 架构师，也不是“模型自主决定一切”。

完成后应支持：

- 用户先确认 Agent 要解决什么问题、什么算成功、什么不能发生。
- 系统展示能力需求和缺口，而不是静默决定。
- 系统优先复用本地可信能力，再检索外部来源，最后才创建新能力。
- Skill 等低风险文本资产可在隔离评测后请求批准；可执行 Tool/Plugin 必须经过更强安全门。
- 个人助手针对任务采用的每个能力配置版本，都能说明使用了哪些能力、专业子 Agent、版本、权限和来源。
- Eval 从用户目标和代表性样例派生，并保留用户可编辑的验收集。
- 系统只能提出候选进化版本；正式 Adopt/Canary/Promotion 遵循决策权和证据门禁。
- 用户随时可以查看为什么选择某模块、候选改了什么、测试结果如何，并退回旧版本。

## 约束

- 用户目标和高影响决策不能由模型静默改写。
- 内部 Benchmark 只是证据，不自动等价于用户满意。
- Agent 不得从 GitHub 找到代码后直接在生产宿主进程安装执行。
- 外部内容、README、Skill、Prompt 和代码都属于不可信输入，不能提升自身权限或改变系统指令。
- 新能力只能缩小或使用用户已授权范围；扩大网络、文件、凭据、外部写入范围必须单独批准。
- 运行中的 Session 不能静默切换 Agent Blueprint。
- 现有 `tool_use/tool_result`、消息持久化形状、Compact、Artifact 引用和恢复不变量必须保留。
- 不复制第二套 Eval、Workflow、Session Event Store、Canary 或 Rollback 系统。
- 第一阶段不热替换生产 Agent Loop、Context 或 Compaction。

## 已收敛的产品问题

| 问题 | 推荐结论 |
| --- | --- |
| 谁定义“好” | 用户定义目标与硬约束；系统协助形成可执行验收；确定性 verifier 与独立 judge 提供证据 |
| Agent 能否自主安装 | 文本 Skill 可自动进入隔离候选；任何生产启用仍需策略门；可执行代码和新增权限必须显式批准 |
| 能否自动创建 Tool | 可以生成候选源码、Manifest 和测试，但只能在隔离工作区构建和验证，不能自动进入生产 |
| 能否自动进化 | 可以自动产生和评测候选；默认不能自动生产晋级 |
| 用户不懂如何写 Benchmark | 系统从目标、正例、反例和真实 Session 提议验收集，用户通过具体例子确认，而非填写复杂指标表 |
| Benchmark 与真实目标冲突 | 真实用户反馈和硬约束优先；指标冲突进入人工复核，不以单一综合分掩盖 |
