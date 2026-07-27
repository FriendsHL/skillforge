# AGENT-CONTEXT-GOVERNANCE — Opus 5 时代的 Agent 上下文与能力治理

> 状态：Design Proposed，待用户批准分期实施
> 模式：Full
> 优先级：P1
> 日期：2026-07-27

## 摘要

在不重写 `AgentLoopEngine`、现有 LLM Provider wire protocol、Skill progressive disclosure、
`ReminderBuilder` 和 Compact 算法的前提下，把 SkillForge 的 System Prompt、Memory、Tool Result、
Skill、MCP、多模态资产和 Compact 状态统一成可追踪、可预算、可恢复、可验证的 Agent Context Runtime。

本需求参考本机 Claude Code 2.1.195、官方 npm 原生包 Claude Code 2.1.220 的静态二进制组装逻辑，
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

## 交付阶段

| Phase | 范围 | 退出门 |
| --- | --- | --- |
| P0 | 真实基线、Prompt 片段观测、Context Breakdown 同源 | 不改变请求字节；能解释每段与每个 Schema 的 token 成本 |
| P1 | 内部 `PromptAssembly` 与信任边界 | Claude/OpenAI-compatible wire shape 不变；低信任内容不能越权 |
| P2 | Memory Provenance、渐进加载与 CAS | 用户事实/助手建议/模型推断可区分；并发更新不丢失 |
| P3 | Tool Result Provenance、Reminder 与 MCP/媒体 Artifact Bridge | 外部结果有来源；MCP 非文本结果进入受管 Attachment |
| P4 | Capability Descriptor、候选路由与审批作用域 | Router 只缩小授权集合；每次暴露/隐藏可解释 |
| P5 | Compact 决策状态、恢复与跨 Provider 验收 | Compact/重启后保留用户决策、资产引用、运行状态与信任级别 |

每期独立 Feature Flag、独立回滚、独立 Full pipeline，不以一个大提交一次上线。

## 明确不做

- 不复制或长期维护 Claude Code/Claude Web 的完整系统提示词。
- 不伪装 SkillForge 是 Claude Code，不依赖特定模型隐藏行为。
- 不把所有 Memory、Skill、MCP Schema 常驻塞进 System Prompt。
- 不用 Prompt 代替 Tool 权限、审批、幂等和服务端参数校验。
- 不在本需求内重写 SSE、`tool_use/tool_result` wire protocol、图片/视频 Provider 或实时语音。
- 不在缺少观测基线前启用意图路由来静默隐藏 Tool。

## 文档

1. [调研与现状审计](research-report.md)
2. [MRD](mrd.md)
3. [PRD](prd.md)
4. [技术设计](tech-design.md)
5. [交付计划](delivery-plan.md)
