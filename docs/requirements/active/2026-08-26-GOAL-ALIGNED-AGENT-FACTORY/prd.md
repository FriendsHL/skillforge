# GOAL-ALIGNED-AGENT-FACTORY PRD

---
id: GOAL-ALIGNED-AGENT-FACTORY
status: prd-proposed
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-08-26
updated: 2026-08-26
---

## 摘要

建设“目标对齐的个人助手能力工厂”：用户始终面对同一个 SkillForge 个人助手，通过自然语言和示例定义目标；助手形成可批准的 Goal Contract，在后台分析能力缺口，优先复用本地能力，按策略从外部发现或创建缺失 Skill/Tool，组织工作流和专业子 Agent，形成版本化任务能力配置，并使用目标导出的 Eval、现有 A/B、Canary、人工采纳和 Rollback 持续改进。

## 目标

- 让非专家用户通过一个个人助手完成不同领域的端到端任务，而不要求先创建、选择或管理多个 Agent，也不要求理解 Tool、MCP、Skill 或 Plugin 技术细节。
- 把用户目标、硬约束、代表性样例和决策权变成版本化 Goal Contract。
- 为现有能力建立统一、只增量适配的 Capability Manifest。
- 建立可复现、可比较、可回滚的 Agent Blueprint revision。
- 支持本地盘点、外部发现、导入、包装和创建缺失能力的受治理流程。
- 让完整 Blueprint 进入现有 Eval、A/B、Canary、Adopt 和 Rollback 闭环。
- 所有关键结论有来源、版本、执行记录和验收证据。
- 保持助手身份、用户长期关系和跨任务记忆连续；专业子 Agent 是助手内部的受限执行角色，不替代主助手。

## 非目标

- 不承诺系统能从一句模糊需求自动推断用户真正想要的一切。
- 不把产品改造成要求用户每次选择“Coding Agent、小说 Agent、视频 Agent”的 Agent 商店。
- 不允许模型静默改变 Goal Contract、权限或生产版本。
- 不在首期建设通用第三方代码 Marketplace 或无审核一键安装。
- 不在首期允许生产 Agent Loop、Context、Compaction 热替换。
- 不把 Builder UI 或无限画布作为执行和持久化真相源。
- 不以单个 LLM Judge 分数决定生产晋级。
- 不重建现有 Skill、Workflow、Eval、Flywheel、Media、Artifact 或 Session 系统。

## 核心概念

### Goal Contract

用户与系统共同确认的目标契约，至少包含：

```text
intendedOutcomes        希望完成什么
representativeTasks     代表性任务/正例
antiGoals               明确不希望发生什么
hardConstraints         必须满足的安全、质量、隐私约束
qualityDimensions       用户在意的质量维度
budget                  成本、时间、调用次数边界
dataPolicy              哪些数据可发送到哪些外部服务
decisionRights          哪些动作系统可做、哪些必须问用户
acceptanceSetRevision   当前验收集版本
```

### Capability Manifest

现有或候选能力的统一说明书。Manifest 是索引和治理层，不要求第一期重写现有接口。

### Specialist Profile 与 Task Capability Plan

- `Specialist Profile`：常驻专业 Agent 的可复用配置，首期直接复用 `AgentEntity/AgentDefinition`。
- `Task Capability Plan`：主 Agent 针对某个 Session/Task 选择哪些常驻 Agent、Tool、Skill 和 Workflow 的临时计划。

用户侧始终由主 Agent 统一承接、解释和交付；任务计划不会替换主 Agent，也不会覆盖其他并行任务。

### Acquisition Proposal

系统发现本地能力缺口后，对“复用、外部导入、包装、创建”给出的候选方案。Proposal 不等于安装。

### Evidence Package

模块或 Blueprint 候选的测试结果、执行 Session、确定性 verifier、judge 结果、成本、安全检查和已知限制。

## 决策权模型

| 决策 | 默认负责人 | 系统行为 |
| --- | --- | --- |
| 澄清用户目标 | 用户 | 系统提问、总结，用户确认 |
| 把目标转成验收样例 | 共同 | 系统提议，用户可编辑并确认 |
| 搜索本地能力 | 系统 | 可自动执行，只读 |
| 搜索 GitHub/SkillHub/MCP | 系统 | 在允许联网范围内自动执行，只读 |
| 创建文本 Skill 候选 | 系统 | 可在隔离区自动生成和评测 |
| 下载外部源码 | 策略控制 | 只进入隔离区，记录来源与 Hash |
| 执行外部代码 | 用户/管理员 | 需要权限、来源和沙箱批准 |
| 新增网络/文件/凭据权限 | 用户/管理员 | 必须显示权限差异并批准 |
| 修改 Goal Contract | 用户 | 系统只能提出修订建议 |
| 创建 Blueprint 候选 | 系统 | 可自动执行，不影响生产 |
| 启动隔离 Eval | 系统 | 在已批准预算内自动执行 |
| Adopt/Canary/Promotion | 用户/管理员 | 默认显式批准；后续仅对低风险策略开放可撤销自动化 |
| Rollback | 系统或用户 | 硬约束失败时可自动回滚并通知 |

## 用户流程

### 流程 A：主 Agent 承接一个真实任务

1. 用户直接告诉主 Agent 要完成的任务，例如实现并发布需求、写长篇小说或生成视频。
2. 普通低风险一次性任务直接执行；长期、多步骤、新能力或高风险任务才生成轻量 Goal Brief。
3. 主 Agent 查询常驻专业 Agent roster 和现有能力。
4. 有合适专业 Agent 时直接委派，主 Agent继续负责整合、验证和交付。
5. 没有合适专业 Agent 时，主 Agent提出新的 Specialist Profile 候选，优先复用本地配置和能力。
6. 只读盘点、搜索、候选生成和隔离 Eval 默认自动；外部代码执行、权限扩大和生产启用需要批准。
7. 新专业 Agent 通过验证后加入常驻 roster，后续相似任务可直接复用。
8. Task Capability Plan 只属于当前 Session/Task，不切换主 Agent，也不覆盖其他并行任务。

### 流程 B：真实使用后的持续优化

1. 生产 Session 反馈与结果关联到 Goal Contract 和 Blueprint revision。
2. 系统发现某一验收维度持续不足，生成有证据的改进 Proposal。
3. 每个候选限制改变少量模块，并记录预期影响和复杂度差异。
4. 候选在冻结验收集和 holdout 上与当前版本比较。
5. 硬约束失败直接淘汰；指标冲突进入人工判断。
6. 用户批准后 Canary，异常自动回滚。

### 流程 C：目标本身发生变化

1. 系统检测到用户要求与当前 Goal Contract 可能冲突。
2. 系统明确展示“这是目标变更，不是普通优化”。
3. 用户确认后创建 Goal Contract 新版本。
4. 原 Blueprint 和历史评测仍指向旧目标版本，不回写历史。

## 功能需求

### FR-1 Goal Elicitation

- 系统必须通过具体任务、正例、反例和取舍问题澄清目标，不要求用户填写工程化配置表。
- 不存在足够信息时，系统必须进入 `needs_user_alignment`，不能继续能力安装或生产组装。
- 系统不得把自己的推断标成用户确认事实。
- Goal Brief 必须引用原始用户消息；字段区分 `USER_STATED/USER_CONFIRMED/SYSTEM_INFERRED/UNKNOWN/CONFLICTING`。
- `UNKNOWN/CONFLICTING` 和低置信度关键字段不得驱动新增权限、外部代码执行或生产能力创建。
- 用户纠正始终覆盖系统推断；Goal 和 Acceptance Set 分别批准。
- 真实用户反馈可以否决离线 Judge 的提升结论。

### FR-2 Goal Contract 与版本

- Goal Contract 必须可查看、编辑、版本化和比较。
- 每个 Blueprint、Eval Run、Evolution Run 必须引用确切 Goal Contract revision。
- 修改硬约束、主要质量权重、数据政策或决策权必须创建新 revision。

### FR-3 Acceptance Set

- 系统可从用户样例、历史 Session 和手工录入生成候选验收任务。
- 验收集必须包含正常案例、边界案例、反例和至少一个 holdout 分区。
- 系统不得通过修改验收集来让候选获得更高分；候选生成和评测期间验收集冻结。
- LLM Judge 必须版本化 Prompt/Model，并与确定性 verifier 分开呈现。

### FR-4 Capability Inventory

- 统一查询 Prompt、Skill、Tool、MCP、Workflow、Media Provider 和其他已支持能力。
- Manifest 至少记录身份、版本、类型、来源、依赖、权限、成本、风险、兼容性和当前可用性。
- 第一阶段通过 Adapter 包装现有 Registry，不迁移其真相源。

### FR-5 Gap Analysis

- Gap Analysis 必须引用 Goal Contract 中的具体 outcome/constraint，不能只输出泛化“建议安装”。
- 结果区分 `satisfied/partial/missing/conflicting/unknown`。
- 每个缺口必须显示判断证据和置信度；低置信度缺口要求用户确认。

### FR-6 Capability Discovery

- 搜索顺序默认本地优先，外部来源必须记录 URL、commit/version、license、publisher 和内容 Hash。
- 外部 README、Skill 和代码一律作为不可信数据处理。
- 搜索结果按目标适配度、维护状态、安全、许可、成本和兼容性分别展示，不用单一不透明总分替用户做决定。

### FR-7 Acquisition Modes

- 支持 `reuse_local/import_skill/wrap_external/create_skill/create_tool` 五种 Proposal。
- Skill 创建必须包含正文、触发说明、适用/不适用范围和 Eval。
- Tool 创建必须包含 Manifest、输入输出 Schema、错误语义、权限、side-effect/idempotency 声明、实现和测试。
- 自动生成 Tool 只能进入隔离开发工作区；生产注册需要独立 Review 和批准。

### FR-8 Supply-chain 与 Sandbox

- 外部能力必须固定来源版本并保存内容 Hash，不允许仅跟随 floating branch/tag。
- 代码执行默认无凭据、无宿主工作区写权限、无未声明网络。
- 安全检查至少包含路径/命令/URL 输入、秘密读取、外部写入、依赖和许可。
- Sandbox 失败或无法建立时 fail closed，不降级为宿主直接执行。

### FR-9 常驻专业 Agent 与任务计划

- 主 Agent 身份和默认入口保持稳定，不能被任务计划替换。
- Specialist Profile 首期复用现有 Agent 配置；需要 A/B/回滚时再增加最小不可变配置快照。
- Task Capability Plan 绑定 Session/Task，不存在 Agent 全局 active pointer，不影响其他并行任务。
- 专业子 Agent 继承当前 Goal/Task 上下文，权限限制为主 Agent 对当前任务授权集合的子集。
- 创建专业 Agent 候选不等于启用；涉及外部代码或新增权限时必须经过批准。

### FR-10 Builder

- 用户能查看目标、能力图、缺口、候选来源、权限差异、Evidence 和版本历史。
- 第一版采用结构化表单/树/DAG，不要求无限画布。
- UI 修改的是 Blueprint draft；执行层只消费已解析、不可变 revision。

### FR-11 Evidence 与对齐判断

- 结果必须按 Goal Contract 的质量维度分别显示，不允许只显示一个综合分。
- 硬约束失败必须阻止晋级，即使平均分更高。
- 模型自评、独立 Judge、确定性 verifier、用户反馈和生产指标必须分来源展示。
- 当证据互相冲突时，状态为 `needs_human_judgment`。

### FR-12 Evolution

- Candidate Composer 只能修改策略允许的 module kinds 和数量。
- 每个候选保存 hypothesis、changed modules、expected impact、permission delta、complexity delta 和 rollback target。
- 最终晋级比较完整 Blueprint；模块归因可使用单模块变化、leave-one-out 或有限消融，但不得把解释当确定因果。
- 默认只能自动生成、评测和淘汰候选，不能自动生产晋级。

### FR-13 Canary 与 Rollback

- 复用现有 Canary/Adopt/Rollback，不建立第二套发布系统。
- Canary 必须绑定 Blueprint、Goal Contract 和 Evidence revision。
- 触发安全、硬约束或显著质量退化时自动回滚，并保留失败证据。

### FR-14 Provenance 与可观测性

- Session/Trace 至少能查询 blueprintRevision、goalRevision、module versions 和 acquisition provenance。
- 外部正文、秘密和完整源码默认不进入模型 Trace。
- 用户能回答“为什么装这个”“本次用了哪个版本”“新版本改了什么”“凭什么认为更好”。

### FR-15 Runtime Strategy Experiment

- Context、Compaction、SubAgent Provider 和 Agent Loop 只在 P6 进入实验候选。
- 实验运行与生产 Session 隔离，不访问真实凭据和高风险副作用 Tool。
- Agent Loop/Compaction 候选必须通过 pairing、persistence shape、recovery、cancel、stream 和 provider compatibility 专项门禁。

## 路线图级验收

- [ ] 用户能通过自然语言和示例建立并确认 Goal Contract；信息不足时流程会停下询问。
- [ ] 用户能看到系统推断与用户确认内容的明确区别。
- [ ] 系统能盘点本地能力并把每个缺口映射到具体目标或约束。
- [ ] 系统能为缺失能力生成复用、导入、包装或创建 Proposal，而不是直接安装。
- [ ] 外部能力固定来源和 Hash；未批准代码不能在生产宿主执行。
- [ ] 至少一个文本 Skill 候选完成自动创建、隔离 Eval、人工批准和 Blueprint 组装闭环。
- [ ] 至少一个 Tool 候选完成生成、沙箱测试和独立 Review，但未批准前不可生产注册。
- [ ] Blueprint revision 可复现，Session 能查询实际使用的 module lock。
- [ ] 完整 Blueprint 能在冻结 acceptance set 上与 baseline 比较，并逐维显示证据。
- [ ] 硬约束失败的高平均分候选不能晋级。
- [ ] 目标发生变化时创建新 Goal revision，历史 Eval 不被重解释。
- [ ] Canary 失败可回滚到准确的旧 Blueprint revision。
- [ ] 外部 Prompt Injection 不能修改权限、Goal Contract 或批准状态。
- [ ] 现有 Tool pairing、Message JSON、Compact、Artifact 和 Recovery 回归通过。

各 Phase 必须拆成独立需求包，拥有自己的依赖、非目标和验收；本总包不作为一次性 P0–P6 开发验收单。

## 依赖

- AGENT-CONTEXT-GOVERNANCE 的 Capability Descriptor、Tool Result Provenance 和 Artifact Bridge。
- 现有 Skill Import、Skill Creator/Eval、Agent Bundle A/B、Canary、Rollback。
- AUTORESEARCH-OPTIMIZATION 的外部发现和来源 ledger。
- HARNESS-BENCHMARK-COMPARISON 的隔离 Runner/Manifest 思路。
- SKILL-EVAL-CHILD-SANDBOX 与 destructive source 防护必须在自动 Eval 扩大前完成或被更强沙箱替代。

## 验证预期

- 后端：Manifest/Blueprint/Goal Contract 单元与集成测试，权限和状态机测试，API roundtrip。
- 前端：Goal Contract、Gap Review、Acquisition Proposal、Blueprint Diff、Evidence 页面组件测试。
- 浏览器：从目标澄清到候选 Agent Canary 的关键交互和 DOM 断言。
- 数据库：不可变 revision、ownership、并发、状态转换、回滚与 migration 集成测试。
- 安全：恶意 README/Skill、路径穿越、SSRF、命令注入、秘密读取、未声明网络、Sandbox fail-closed。
- 活体：至少一个非 Coding 专业 Agent 和一个 Coding Agent dogfood，验证架构不绑定具体垂域。
