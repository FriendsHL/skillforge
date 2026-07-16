# Tech Design — Agent 向 App 用户交付文件

> 状态：implemented；真实 Agent + iOS 真机文件验收待运行。

## 现有链路

`ArtifactWorkspaceService` 为每次 run 创建受管目录并向 system prompt 注入路径 → Agent 写文件 →
`PublishChatArtifact` 导入 `t_chat_attachment` → `SkillResult.artifacts`/`ToolExecutionOutcome` 传给
Agent Loop → 最终 assistant message materialize attachment refs → ChatService 持久化/广播 → mobile
message DTO 解码 → iOS 鉴权下载、预览和分享。

## 候选根因

1. 工具未进入目标 Agent 的可用 registry，模型根本看不到。
2. prompt 虽有指令，但模型生成文件后未调用发布工具。
3. 文件写在 workspace 外，被安全边界正确拒绝。
4. 工具成功，但 artifact sidecar 未并入最终 assistant message。
5. 后端消息正确，iOS 使用的版本、解码或下载请求失败。

## 根因结论（2026-07-16）

- 全局 registry 已注册 `PublishChatArtifact`，每次普通 Chat run 也会创建 artifact workspace 并注入
  system prompt。
- 运行时会用 `t_agent.tool_ids` 设置 `allowedToolNames`。当前只有 Main Assistant 含
  `PublishChatArtifact`；最近使用的 Research Agent 以及 Design/Code 等普通 Agent 均不含该工具。
- 数据库没有任何 `origin=agent_generated` 记录，因此尚未进入 artifact sidecar、消息持久化或 iOS
  下载阶段；首要根因是工具不可见，而不是 iOS 渲染失败。

## 实施方案

### A. 授予所有 active user Agent（推荐）

新增幂等 Flyway 迁移，对 `agent_type='user' AND status='active'` 且存在非空 JSON tool allowlist 的
Agent 追加 `PublishChatArtifact`。不触碰 system/eval Agent，也不改变空 allowlist 的“不限制”语义。
同时补迁移测试和 Agent 运行时工具可见性测试。

优点是符合“App 中选择任意普通 Agent 都可交付文件”的产品预期；安全边界仍由每次 run 的受管
workspace、session/user/tool-use 绑定和文件类型校验负责。

### B. 仅授予内置 Design/Code/Research Agent

变更更窄，但用户新建的普通 Agent 仍会复现相同问题，能力规则难以解释，不推荐。

### C. 绕过白名单全局强制注入

需要修改 `ChatService` 的运行时过滤语义，并削弱 Agent 显式最小权限配置，不采用。

## 实施与验证记录（2026-07-16）

- 新增 V172，只更新 active `agent_type=user` 且显式非空的 JSON array 工具白名单；NULL、空数组、
  inactive user 和 system Agent 均保持原样，重复执行不重复追加。
- 当前数据库迁移更新 4 个缺权限 Agent；查询确认 5 个内置 user Agent 均可发布，system Agent 不可发布。
- RED：迁移 IT 因 V172 资源不存在失败；GREEN：实现后定向附件链路测试 15/15 通过。
- 完整服务端测试：3325 个，0 失败，175 跳过，`BUILD SUCCESS`。
- 未运行：真实模型生成 PNG/文档以及 iOS 真机下载、Quick Look 和分享；这些依赖用户侧 App 会话与真机交互。

## 取证顺序

1. 选一个普通 Chat session，要求生成简单 PNG 和 CSV/PDF。
2. 检查模型请求中的 tool schema 是否含 `PublishChatArtifact`。
3. 检查 tool_use/tool_result、工具返回的 attachment id 和数据库附件状态。
4. 检查最终 `t_session_message.content_json` 是否含唯一 attachment ref。
5. 对照 mobile REST 原始 JSON、WebSocket event 与 iOS view model。
6. 用真机验证下载、Quick Look/图片预览和分享。

## 方案选择门

- 若工具不可见：修 registry/能力装配并加 Agent 级契约测试。
- 若工具可见但模型不调用：加强生成产物协议和“未发布不得声称已交付”的收尾检查。
- 若后端正确而 App 不显示：只修 mobile DTO/iOS reducer/render/download 层。
- 不采用工具内直接 append message；该路径会破坏最终消息对账和 tool 配对不变量。

## 验证计划

- Core/server：工具可见性、artifact sidecar、消息持久化、幂等和权限测试。
- iOS：URLProtocol 下载测试、附件 reducer 测试、XCUITest 预览/分享入口。
- 最终门：真后端 + 真 Agent + 真机 image/document E2E。
