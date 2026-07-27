# MRD — Agent 上下文与能力治理

## 1. 背景

SkillForge 已从文本聊天发展为包含 Tool、Skill、MCP、Memory、Compact、多 Agent、Workflow 和多模态的
Agent Runtime。能力数量增长后，主要瓶颈不再是“有没有功能”，而是模型每轮能否获得正确、可信、适量、
可恢复的上下文。

若继续由各模块独立拼接字符串，会出现：

- 外部资料在 System Prompt 位置获得不恰当的权威感。
- Tool Schema、Memory 和 Reminder 持续增加 token 成本。
- 管理端 Context Breakdown 与真实 Provider 请求不一致。
- Compact 后保留了文本，却丢失来源、用户决策和运行状态。
- 新 Provider、MCP 非文本结果和多模态资产需要重复实现上下文适配。

## 2. 目标用户

### Agent 使用者

希望 Agent 能记住相关事实、调用正确能力、在长任务中持续工作，并能解释为什么需要确认或为什么某项能力
不可用。

### Agent/Skill 配置者

希望知道某个 Agent 实际加载了哪些 Prompt、Memory、Skill、Tool 和 MCP，以及它们的 token 成本和来源。

### 平台运维与开发者

希望快速区分模型问题、Provider 问题、路由问题、Compact 问题和 Prompt Injection，并能安全灰度、回滚。

## 3. 用户问题

1. “为什么 Agent 把之前助手建议的话当成我已经决定的事实？”
2. “为什么 Agent 明明配置了 Tool，却没有调用，或上下文里塞了太多无关 Tool？”
3. “网页、MCP、文件里出现指令时，是否可能改变平台行为？”
4. “Compact 或重启后，Agent 是否还能找到之前图片、任务和明确决定？”
5. “为什么 Context 页面显示的 token 和真实请求不一致？”
6. “换成 Opus 5、OpenAI-compatible 或其他 Provider 时，是否需要重写整套 Prompt？”

## 4. 产品目标

- G1：同一轮请求的所有上下文片段都有来源、权威、信任、预算和生命周期。
- G2：管理端展示的数据来自真实 Prompt Assembly，而不是估算副本。
- G3：用户确认事实、助手建议、模型推断和外部事实不会混为一类。
- G4：Tool/Skill/MCP/Media 的暴露不超过既有授权，并可解释原因。
- G5：Compact 和恢复保留决定、来源、资产和运行状态，不提升内容权威。
- G6：Claude、OpenAI-compatible 和后续 Provider 共享内部模型，但保留各自 wire protocol。
- G7：迁移过程中请求行为可 shadow 比对，每期可独立回滚。

## 5. 成功指标

### 正确性

- Prompt Assembly 与实际发送内容片段覆盖率 100%。
- Router 暴露集合超出授权集合的次数为 0。
- Memory 并发静默覆盖次数为 0。
- Compact 后资产 ID、用户决定和 trust level 保留率 100%。

### 安全

- Memory、网页、文件、MCP、SubAgent 五类 Prompt Injection 回归全部通过。
- 客户端无法声明或提升 `authority`、`trustLevel`。
- Trace 和管理端不泄露 Secret 或默认返回完整低信任正文。

### 成本与体验

- P1 相同业务内容的 System Prompt token 增幅不超过 5%。
- P4 在高 Tool/MCP Agent 上，Schema token P50 降低至少 25%，任务成功率不低于基线 2 个百分点以上。
- 未触发 Reminder 的请求新增 token 为 0。
- Context Breakdown 与 Provider 前最终估算偏差保持在既有 TokenEstimator 可解释范围内。

## 6. 约束

- 保持 Java 17、Spring Boot、现有 JPA/Flyway 和 Provider 架构。
- 首期不改变持久化消息 JSON shape。
- 首期不改变 Claude/OpenAI-compatible 请求的语义顺序和 Cache Boundary。
- 任何 Tool 路由都不能替代执行时二次授权。
- 多模态正文、Base64 和 Provider 临时 URL 不进入长期 Prompt 或 Compact Summary。

## 7. 非目标

- 复制 Claude Code 产品行为。
- 训练或微调模型。
- 建立通用向量数据库平台。
- 重写 Task Recovery、Workflow Engine 或 Media Provider。
- 以 LLM 分类器作为唯一权限决策器。
