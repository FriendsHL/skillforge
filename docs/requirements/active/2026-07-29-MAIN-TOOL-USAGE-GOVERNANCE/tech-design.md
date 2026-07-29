# 技术设计：Main Agent 工具使用治理

## Prompt 分层

- Global Tool Usage Guidelines：只放跨任务、高频、需要在多个工具之间做选择的规则。
- Tool Description：只放工具功能、关键参数、返回/副作用契约。
- Skill / 专用 Agent：放 marketplace 安装步骤、代码注册流水线、复杂 workflow 手册。

## 配置变更

新增 Flyway 迁移，从 Main Assistant 的 `tool_ids` JSON 数组删除：

- RegisterScriptMethod
- RegisterCompiledMethod

不修改 Code Agent。Main Assistant 遇到 Hook 代码实现时，通过 AgentDiscovery/SubAgent 委派 Code Agent。

## Prompt 规则

新增六组短规则：

1. WebSearch / WebFetch。
2. memory_search / GetSessionMessages / GetTrace。
3. AgentDiscovery / SubAgent / Team*。
4. GetAgentConfig / GetAgentHooks / CreateAgent / UpdateAgent / ProposeHookBinding。
5. ScheduledTask CRUD。
6. PublishChatArtifact / PublishInteractiveArtifact / GenerateImage / EditImage / GenerateVideo。

## Description 分层

- RunWorkflow：保留异步语义、三种模式、Inline DSL 原语、最小脚本示例和沙箱限制；删除重复解释和大型业务示例。
- ImportSkill：描述导入“已安装 skill 目录”的校验、扫描、复制、持久化和注册职责；不嵌入具体安装命令，只指向需要用户确认的受控安装流程。

## 不变项

- ToolSearch 和 ToolCatalog 分类。
- GenerateVideo 的 `ARK_VIDEO_ENABLED` 条件注册。
- Tool Schema 和 REST API。
- Code Agent 工具授权。
