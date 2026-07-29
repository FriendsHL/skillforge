# AGENT-CONTEXT-GOVERNANCE — Opus 5 时代的 Agent 上下文与能力治理

> 状态：P0–P2、P2.5、P4–P6 已交付；P3 按产品决策跳过；P7–P9 待分期实施
> 模式：Full
> 优先级：P1
> 日期：2026-07-27

## 摘要

在不重写 `AgentLoopEngine`、现有 LLM Provider wire protocol、Skill progressive disclosure、
`ReminderBuilder` 和 Compact 算法的前提下，把 SkillForge 的 System Prompt、Memory、Tool Result、
Skill、MCP、多模态资产和 Compact 状态统一成可追踪、可预算、可恢复、可验证的 Agent Context Runtime。

本需求参考本机 Claude Code 2.1.220、官方文档和官方 Changelog 的上下文组装逻辑，
以及社区捕获的 Claude Opus 5 Web/Mobile Prompt。社区 Prompt 只用于交叉验证，不复制其文本，也不把
Web Chat 的产品规则误当 Claude Code Harness 规则。

## 已确认的产品判断

1. Claude Code 2.1.220 已包含 `claude-opus-5`、Opus 5 1M context 和模型条件分支。
2. Claude Code 没有一份固定超长 Prompt；它按身份、Harness、环境、Memory、Agent、Skill、Tool 和
   Feature Flag 组装片段。
3. SkillForge 已有 stable/dynamic Prompt、Skill Loader、Tool 二次授权、Reminder budget、Full Compact、
   Root/SubAgent 恢复和 Attachment Reference，不应推倒重做。
4. 当前最大缺口是各模块在进入模型前被压成字符串，来源、信任、预算、有效期和恢复语义无法统一表达。
5. Tool Schema 是首要上下文成本之一；但路由优化必须晚于授权、来源和观测基线，避免静默隐藏关键能力。

## 当前基线

- P0 观测基础已随 `0ec321b4` 交付：Prompt Fragment Observation、System Prompt stable/dynamic
  分段、Tool Schema 成本和 Context Breakdown 同源能力已经存在。
- P1 已完成：`ContextAttachment`、`PromptAssembly`、兼容 Renderer，以及
  Global/Agent/Soul/Tool Guidance/Behavior/Runtime/Session Context/User Memory 的结构化迁移。
  Context Breakdown 已输出 authority、trustLevel、lifecycle 和 compactPolicy；Provider wire shape 保持不变。
- 当前 `ReminderEntry` 仍只有 `text + estimatedTokens`，尚不是结构化 Context Attachment。
- 当前 Skill 正文通过 `Skill` Loader 渐进加载，但 Compact 后没有独立 Invocation Registry。
- 当前 Java/MCP Tool Schema 仍由 `collectTools()` 基本全量注入，没有通用 ToolSearch、
  `discoveredToolIds` 或 Deferred Schema Compact 恢复。
- 当前没有 Claude Code 风格的嵌套 CLAUDE.md/路径规则注册表；Global/Agent Instruction 仍直接参与
  System Prompt 组装。
- P2.5 已完成 Prompt/Memory 收敛：Global Prompt 只保留平台规则，Main Assistant 只保留角色职责；
  Artifact Workspace 进入 dynamic runtime；自动记忆仅注入最近 6 条 `ACTIVE + CONFIRMED`
  长期事实，未确认摘要与短期信息通过 `memory_search → memory_detail` 按需加载。

## 交付阶段

| Phase | 范围 | 退出门 |
| --- | --- | --- |
| P0 | 真实基线、Prompt 片段观测、Context Breakdown 同源 | **已交付**；不改变请求字节，能解释主要片段与 Schema 成本 |
| P1 | 内部 `PromptAssembly`、`ContextAttachment` 与兼容 Renderer | **已交付**；Session/Memory 已迁移，Web/File/RAG/SubAgent 已加低信任边界、shadow hash 与回滚开关 |
| P2 | Structured Reminder V2 | Reminder 可按事件放置、去重和失效；普通用户不能伪造内部 Reminder |
| P2.5 | Prompt/Memory 收敛 | **已交付**；平台与 Agent 职责去重，动态运行信息不污染稳定前缀，短期记忆不再常驻 |
| P3 | Instruction Registry、嵌套指令和去重 | **跳过**：SkillForge 当前不依赖 CLAUDE.md，后续倾向移除而非扩建该机制 |
| P4 | ToolCatalog、ToolSearch 与 Deferred Schema | Ark/OpenAI-compatible 可按需发现 Tool；关键能力不会被静默隐藏 |
| P5 | Tool/Skill Compact 恢复 | Schema 从权威 Registry 重建；Skill 按预算重挂；不伪造 tool pairing |
| P6 | Memory Provenance、渐进加载与 CAS | 用户事实/助手建议/模型推断可区分；并发更新不丢失 |
| P7 | Tool Result Provenance、MCP/媒体 Artifact Bridge | 外部结果有来源；MCP 非文本结果进入受管 Attachment |
| P8 | Capability Descriptor、候选路由与审批作用域 | Router 只缩小授权集合；每次暴露/隐藏可解释 |
| P9 | Compact 决策状态、恢复与跨 Provider 验收 | Compact/重启后保留用户决策、资产引用、运行状态与信任级别 |

每期独立 Feature Flag、独立回滚、独立 Full pipeline，不以一个大提交一次上线。

## 明确不做

- 不复制或长期维护 Claude Code/Claude Web 的完整系统提示词。
- 不伪装 SkillForge 是 Claude Code，不依赖特定模型隐藏行为。
- 不把所有 Memory、Skill、MCP Schema 常驻塞进 System Prompt。
- 不用 Prompt 代替 Tool 权限、审批、幂等和服务端参数校验。
- 不在本需求内重写 SSE、`tool_use/tool_result` wire protocol、图片/视频 Provider 或实时语音。
- 不在缺少观测基线前启用意图路由来静默隐藏 Tool。
- 不把 Tool Schema 或完整 Skill 正文交给 Compact 模型复述；Compact 只保存稳定身份，正文从权威来源重建。
- 不为了模仿 Claude Code，把所有 SkillForge 指令强制改名为 CLAUDE.md。

## 文档

1. [调研与现状审计](research-report.md)
2. [MRD](mrd.md)
3. [PRD](prd.md)
4. [技术设计](tech-design.md)
5. [交付计划](delivery-plan.md)
