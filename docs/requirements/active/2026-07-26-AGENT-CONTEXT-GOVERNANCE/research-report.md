# Research Report — Claude Code 2.1.220 / Opus 5 与 SkillForge

## 1. 调研范围

### 本地与官方制品

- 本机安装：Claude Code `2.1.195`，Mach-O arm64。
- 官方 npm 元数据：`@anthropic-ai/claude-code@2.1.220`。
- 官方原生包：`@anthropic-ai/claude-code-darwin-arm64@2.1.220`。
- 2.1.220 构建时间：`2026-07-24T22:17:45Z`。
- 2.1.220 Git SHA：`4073f59596e272f39393db4f96abc5f4b10eff21`。

本机自更新因下载问题没有完成，分析使用下载到临时目录的官方原生二进制，没有替换现有可执行文件。

### 社区样本

- `Eversmile12/leaked-llm-prompts/Anthropic/opus-5.md`：标明为 Claude Web/Mobile Chat Prompt。
- `asgeirtj/system_prompts_leaks/Anthropic/Claude Code/claude-code-opus-4.8.md`：用于核对 Claude Code
  片段边界和动态环境内容。

社区材料不具官方规范效力，只用于与官方二进制静态字符串交叉验证。

## 2. 已验证事实

Claude Code 2.1.220 原生二进制包含：

```text
claude-opus-5
Opus 5
Opus 5 with 1M context
claude-fable-5
claude-sonnet-5
```

同时可识别出以下静态/动态组装区域：

```text
Identity
System/Harness
Communicating with the user
Doing tasks
Using tools
Tone and output style
Environment
Scratchpad
Context management
Memory
CLAUDE.md
Session/git context
Agents
Skills
Tool definitions
```

Prompt 内容会根据模型、工具集合、权限模式、输出风格、运行表面、Feature Flag、Memory/Skill/Agent
可用性变化。因此“静态系统提示词”应理解为可组合静态片段，不是一份可直接复制的 Markdown。

## 3. Claude Code 2.1.220 的关键机制

### 3.1 信任来源

- Harness 注入的标记与普通用户文本区分。
- Tool Result 可能来自外部来源，模型被要求识别 Prompt Injection。
- Hook Output 有明确来源语义。
- Recalled Memory 是背景资料，需要重新验证易变化的文件、函数和开关。

### 3.2 渐进披露

- Skill 列表常驻，详细 Skill 按需加载。
- Agent 类型只暴露描述和工具范围。
- Deferred Tool 只先暴露名称，再按需发现 Schema。
- Memory 使用索引与单条内容分离的模型。

### 3.3 上下文连续性

- 长会话允许自动 Compact，不要求提前结束任务。
- Compact Prompt 特别保护用户反馈、改变方向的决定、安全约束和未完成状态。
- 已经确认的事实不应在 Compact 后重新推导。

### 3.4 行为与验证

- 区分 verified 与 assumed。
- 不把范围外重构混入当前任务。
- UI 变更需要真实使用验证，而不只依赖测试和类型检查。
- 对外、不可逆、高风险操作要求明确或持久授权。

## 4. SkillForge 已具备的基础

| 领域 | 已有能力 |
| --- | --- |
| Prompt | global/agent/soul/tools/behavior + stable/dynamic cache boundary |
| Skill | `SessionSkillView`、Loader、系统/用户 Skill、Canary、执行时二次授权 |
| Tool | Java Tool、Hook、Approval、Artifact、`tool_use/tool_result` 配对 |
| MCP | CRUD、stdio/HTTP、Agent allowlist、Schema 注册和执行分发 |
| Memory | FTS/vector、importance、reflection、proposal、consolidation |
| Reminder | 多 Source、debounce、总 token budget、异常隔离 |
| Compact | Soft/Full/preemptive、overflow 最多三次 Full Compact、Checkpoint/Replay |
| Recovery | Root/SubAgent 启动恢复和恢复次数上限 |
| Multimodal | `image_ref`、PDF/Office materialization、Ark 图片、Attachment |

## 5. 真实缺口

1. `SystemPromptBuilder` 与 `ContextBreakdownService` 分别重建内容，存在统计漂移。
2. Memory 进入模型前退化为文本和 ID，来源置信度不能随请求传递。
3. Reminder、Memory、网页、文件、SubAgent 结果缺少统一 authority/trust 表达。
4. MCP 图片/Resource 目前主要变成文本占位，没有进入 Attachment Runtime。
5. Tool/Skill/MCP/Media 缺少统一的副作用、费用、异步、恢复、媒体类型和可用性描述。
6. Compact 擅长保护消息形状，但摘要没有统一保存用户决策、否决方向和 verified/assumed 状态。
7. 多实例恢复、Workflow Startup Recovery 属于相关但独立的可靠性需求，不应混入 Prompt 重构。

## 6. 对社区 Opus 5 Prompt 的使用边界

可借鉴：

- Memory 相关性、敏感信息和陈旧性原则。
- 外部内容作为数据而非高权限指令。
- 多模态文件引用与最终呈现分离。
- 简洁且条件化的产品规则。

不可直接迁移：

- Claude 产品身份和消费者产品说明。
- Claude Web 专属工具 Schema、广告、健康、金融等产品政策。
- 未经官方证实的产品事件、URL 和模型描述。
- 依赖 Claude Harness 私有标签才成立的安全假设。

SkillForge 必须以自己的授权、持久化和 Provider 契约作为真相源。
