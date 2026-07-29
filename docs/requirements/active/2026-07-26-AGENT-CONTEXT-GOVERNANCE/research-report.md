# Research Report — Claude Code 2.1.220 / Opus 5 与 SkillForge

## 1. 调研范围

### 本地与官方制品

- 本机安装：Claude Code `2.1.220`，Mach-O arm64。
- 官方 npm 元数据：`@anthropic-ai/claude-code@2.1.220`。
- 官方原生包：`@anthropic-ai/claude-code-darwin-arm64@2.1.220`。
- 2.1.220 构建时间：`2026-07-24T22:17:45Z`。
- 2.1.220 Git SHA：`4073f59596e272f39393db4f96abc5f4b10eff21`。

本机当前可执行文件与分析版本均为 `2.1.220`。

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
- MCP/低频 Tool 的完整 Schema 可通过 ToolSearch 延迟发现；原生 Tool Search 使用
  `defer_loading + tool_reference`，OpenAI-compatible Harness 需要在下一轮 `tools[]` 中正式补入 Schema。
- Memory 使用索引与单条内容分离的模型。

### 3.3 Instruction 与 Reminder

- 用户级和项目根 CLAUDE.md 在 Session 启动时加载正文；嵌套 CLAUDE.md 和 path-scoped rules
  在访问对应目录/文件时加载。
- 指令加载具有 `session_start`、`nested_traversal`、`path_glob_match`、`include`、`compact`
  等原因；路径身份用于去重，不能只按正文 Hash 去重不同作用域的文件。
- Claude Code 内部把动态 Harness Context 作为 meta attachment/context part 管理，但 Provider
  边界仍可能渲染成 `<system-reminder>` 文本、meta user content 或中途 system block。
- Reminder 的价值是记录来源、位置、生命周期、去重和截断语义，不是新增一个公开
  Anthropic `system_reminder` Content Block。

### 3.4 Compact 连续性

- 长会话允许自动 Compact，不要求提前结束任务。
- Compact Prompt 特别保护用户反馈、改变方向的决定、安全约束和未完成状态。
- 已经确认的事实不应在 Compact 后重新推导。
- Project-root CLAUDE.md 和 Auto Memory 在 Compact 后从权威来源重载；嵌套指令在再次访问路径时重载。
- ToolSearch 已发现工具通过 Compact 外的 discovered-tool 状态恢复，完整 Schema 从 Tool Registry
  重新解析，不能依赖 Summary 复制 JSON Schema。
- Skill Invocation 在 Compact 后按最近调用优先重挂：每个 Skill 最多保留前 5,000 tokens，
  合计预算 25,000 tokens；超预算的旧 Skill 可以被丢弃并允许重新调用。

### 3.5 行为与验证

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
8. `ReminderEntry` 当前只有文本和 token 估算，缺少来源、placement、lifecycle 和 compact policy。
9. Tool Schema 当前基本全量注入；Skill 虽渐进加载，但 Tool/Skill/Instruction 都缺少统一的 Compact
   Runtime State Snapshot。

## 6. 对 SkillForge 的新增结论

1. 先建立内部 `ContextAttachment`，再改变 Provider placement；第一批必须保持 wire byte-shape。
2. Reminder V2、Instruction Registry 和 ToolSearch 是三个不同子系统，不能用一个“渐进加载”概念代替。
3. Deferred Tool 的搜索结果只有在下一轮成为正式 `tools[]` Schema 后才可调用；普通 tool result
   返回 Schema 文本不具备 Function Calling 约束力。
4. Compact 必须分开保存业务 Continuity 与 Harness Runtime State：
   `loadedInstructionIds`、`discoveredToolIds`、`invokedSkillIds` 不能只存在自然语言 Summary 中。
5. Tool/Skill/Instruction 恢复优先保存稳定 ID 和版本 Hash，正文从 Registry/文件/Skill Package 重建。

## 7. 对社区 Opus 5 Prompt 的使用边界

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
