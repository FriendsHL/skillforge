# PRD — 持久化 Task、目标连续性与 Artifact 恢复

## 1. 产品原则

1. 最新用户消息高于 Reminder；Task 是执行状态，不是第二份对话真相。
2. 简单问题和普通追问不使用 Task；复杂、多步骤、需要跨轮跟踪的工作才使用。
3. Tool success 不等于用户目标完成，Tool failure 也不等于新增用户目标。
4. 已完成结果未满足原要求时重开原 Task；原要求已满足而用户增加范围时创建新 Task。
5. Tool Schema 放字段契约，Global Prompt 放稳定决策，Main Prompt 放 Main Agent 行为，Reminder 只放动态状态。

## 2. Task 工具契约

### FR-1 TaskCreate

输入：

- `subject`：短标题，必填。
- `description`：完整要求和完成定义，必填。
- `activeForm`：进行中 UI 文案，可选。
- `metadata`：结构化扩展数据，可选。

服务端生成 `taskId`，初始状态固定为 `pending`。

### FR-2 TaskUpdate

输入：

- `taskId`：必填；兼容修复常见别名 `id`、`task_id`。
- 可更新 `status`、`subject`、`description`、`activeForm`、`owner`、`metadata`。
- `addBlocks` / `addBlockedBy` 增量增加依赖。
- 状态仅允许 `pending | in_progress | completed | deleted`。

兼容修复 `active_form -> activeForm`；Schema 不宣传别名。

### FR-3 TaskGet / TaskList

- `TaskGet(taskId)` 返回任务详情、双向依赖和审计时间。
- `TaskList` 返回当前 Session 的任务，默认包含全部非 deleted；可按状态过滤。
- 返回按创建时间稳定排序，并标明阻塞状态。

### FR-4 状态与依赖约束

- 任务初始为 pending。
- 任务存在未完成 `blockedBy` 时不能进入 in_progress。
- completed 可因用户指出原验收未满足而重开为 in_progress/pending。
- deleted 表示不再相关，Reminder 默认不展示；审计记录保留。
- 依赖不得自环或形成有向环。
- 同一 Session、同一非空 owner 最多一个 in_progress；无 owner 的 Main Agent 任务同样最多一个 in_progress。
- 更新使用乐观锁；冲突返回可重试的结构化错误。

## 3. 用户消息与 Task 行为矩阵

| 用户事件 | Task 操作 |
| --- | --- |
| 普通追问、解释性问题 | 不创建、不更新 Task |
| 修改当前进行项 | 更新当前 Task 的 description/activeForm，保持 in_progress |
| 新增独立功能 | 新建 pending；不抢占当前项 |
| 明确“先做这个” | 当前项退回 pending，新项/目标项设 in_progress |
| 指出已交付结果不满足原要求 | 重开受影响的 completed Task |
| 已完成后增加新范围 | 保留完成事实，新建 Task |
| “继续” | 继续当前 in_progress；若没有则选择可执行 pending |
| “暂停/先停下” | 当前项退回 pending，不伪造 completed |
| 取消某项 | status=deleted |
| Tool 失败 | 更新执行说明或保持状态；不得把修 Tool 自动建成业务 Task |

## 4. Prompt 与 Reminder

### FR-5 稳定 Task Guidelines

Global Prompt 使用中文说明：

- 复杂、多步骤、需要跨轮跟踪时使用 Task；简单任务与纯对话跳过。
- 创建后开始工作前设 in_progress，只有完成并验证后设 completed。
- 用户改变范围、优先级或验收时才增量更新；不要因普通追问反复改计划。
- 用户否定旧交付时重开，新增范围时新建，暂停时不伪造完成。
- Tool failure 是执行事实，不是用户目标。

字段、状态和依赖的完整说明只存在于四个 Tool Description。

### FR-6 Main Agent 行为

Main Agent Prompt 包含第 3 节矩阵的简明版本，并强调最终回复围绕最新用户目标。它不引入自动 stale、revision、
objective/acceptanceCriteria 独立状态机。

### FR-7 Task Reminder

- 仅存在 pending/in_progress 或因依赖被阻塞的任务时注入。
- 只注入简洁摘要：taskId、subject、status、activeForm、owner、blockedBy。
- 明确“以当前用户消息为准；若本消息与任务无关，可以忽略 Reminder”。
- 全部 completed/deleted 时不注入。
- Compact 后从数据库重新计算，不依赖被压缩掉的 Tool 消息。

## 5. TodoWrite 迁移与兼容

### FR-8 单协议暴露

- 删除 TodoWrite runtime、TodoStore、TodoListSource、Bean 注册和面向当前模型的 Prompt 文本。
- 不同时向模型暴露 TodoWrite 与 Task。
- 不迁移历史 Todo 内容为新 Task，避免改变旧 Session 语义。
- 历史 trace/message 中的 TodoWrite 名称和参数继续由 Dashboard/iOS 通用 Tool 卡片渲染。
- 若持久化枚举或反序列化兼容仍需要旧 reason 值，则只保留读取能力，不产生新写入。

## 6. Artifact 通用规范与错误契约

### FR-9 两种一级发布模式

- Template：只传 `template_id + initial_data`，不传 `entry_file`。
- Custom：在当前 run artifact workspace 写入新的最终 HTML，再传相对 `entry_file + state_schema`；模型不得复制、
  重建或猜测 workspace 的绝对路径。运行时继续读取历史消息中的 `file_path`，但不再把它暴露给新模型调用。
- 模板是快速路径，不是能力边界；Agent 可根据需求自行设计 Custom 页面。
- 修改既有 Personal App 时可传 `replace_artifact_id`。服务端创建新 Artifact，并通过派生字段关联旧版本；旧消息
  引用的内容保持不可变，不做原地覆盖。

### FR-10 Custom 页面规范

- 同时适配 iPhone 与桌面；有清晰标题、摘要、层级、导航/展开细节和原文链接。
- 转义不可信数据；禁止危险外部脚本和未声明能力；状态必须符合受支持的 `state_schema`。
- 发布前统一预检 workspace、文件存在性/大小、HTML 能力、schema 和安全规则。
- HTML 必须是完整文档：显式 doctype、html/body、闭合文档边界和非空可渲染 body；CDATA 包装、HTML 片段和
  截断文档不得发布成功。

### FR-11 结构化结果

失败最少返回：

```json
{
  "success": false,
  "errorCode": "ARTIFACT_SCHEMA_INVALID",
  "errorType": "VALIDATION",
  "retryable": false,
  "failedField": "state_schema",
  "suggestedAction": "修正 schema 后重新发布",
  "recoveryAction": "CORRECT_ARGUMENTS",
  "preserveUserGoal": true
}
```

成功只返回 `artifactId`、可访问地址、状态和必要元数据。结果不得回显大段 HTML 或 base64。失败建议不得要求
Agent 把修 Tool 当成新的业务目标。

## 7. Dashboard 与 iOS

### FR-12 Task 展示

- Chat 展示总体完成数、当前 `activeForm`、阻塞状态；可展开任务列表。
- TaskCreate/TaskUpdate 的 Tool 卡片显示增量变化。
- WebSocket 推送 Task 变更，前端节流合并。
- Task 首次加载或订阅失败只降级任务区域，不阻塞 Chat 页面。
- 两端共享同一 API DTO 语义，并保留历史 TodoWrite Tool 卡片。

## 8. Session 回放验收

使用 `7f623dc6-3610-46c2-9b95-6f3e0e1f4764` 的脱敏 fixture，确定性断言：

1. 普通追问不动 Task。
2. 修改当前要求更新同一 Task。
3. 独立需求新增 pending。
4. 明确优先级会切换 in_progress。
5. 用户否定交付会重开。
6. Artifact failure 不改变业务目标。
7. Custom Artifact 修正后可以成功发布。
8. Compact 与服务重启后 Task 仍存在并可继续。
9. 历史 TodoWrite 消息可展示。
10. 最终回复覆盖最新用户目标，而不是最后一个 Tool error。
