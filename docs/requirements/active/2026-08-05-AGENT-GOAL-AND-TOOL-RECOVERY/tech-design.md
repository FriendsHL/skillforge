# 技术设计 — 持久化 Task、目标连续性与 Artifact 恢复

## 1. 架构边界

```text
LLM Task tools
  TaskCreate / TaskUpdate / TaskGet / TaskList
                    |
                    v
SessionTaskService  ----> Task domain events ----> WebSocket / Dashboard / iOS
       |                                |
       v                                v
t_session_task               TaskReminderSource
t_session_task_dependency          |
       ^                            v
       +------ restart / compact recovery
```

Tool 只做参数适配与 `SkillResult` 渲染；状态、权限、依赖、并发和事务均收口到 Service。未来 Team coordinator 复用
owner/依赖字段，但不在本包引入自动领取或调度。

## 2. 数据模型

### 2.1 `t_session_task`

| 字段 | 设计 |
| --- | --- |
| `id` | UUID 字符串主键，对外即 `taskId` |
| `session_id` / `user_id` | 所属 Session/用户，非空并建组合索引 |
| `subject` | 短标题，非空 |
| `description` | 完整要求与完成定义，非空 text |
| `active_form` | 非空；Tool 输入可省略，服务端默认使用 `subject` |
| `status` | pending/in_progress/completed/deleted |
| `owner` | 可空；Main Agent 未分配时使用统一空 owner 规则 |
| `metadata` | JSONB，大小受限 |
| `version` | JPA `@Version` |
| `created_at` / `updated_at` | `Instant` |

索引至少覆盖 `(session_id, status, created_at)` 和 `(session_id, owner, status)`。一 owner 一个 in_progress 由
数据库可表达时使用部分唯一索引，并由 Service 提供可读校验；空 owner 用规范化键或等价表达保证 Main Agent
同样受约束。

### 2.2 `t_session_task_dependency`

- `(task_id, blocked_by_task_id)` 联合主键/唯一约束。
- 两端均外键到 `t_session_task`，级联删除只作用于物理清理；正常业务使用 deleted。
- 禁止自依赖；Service 在同一事务内做图遍历防环。

## 3. Service 与 API

### 3.1 `SessionTaskService`

- `create(sessionId, userId, command)`。
- `update(sessionId, userId, taskId, patch)`；空字段表示不改，不用 null 覆盖。
- `get` / `list` 始终按用户与 Session 校验，防止 IDOR。
- 切换 in_progress 时可原子地把同 owner 当前项退回 pending；仅用户/模型显式切换时发生。
- 依赖校验、循环检查、元数据/文本长度限制和乐观锁冲突在 Service 层完成。
- public 写方法使用 `@Transactional`；时间使用 `Instant`；使用 Spring `ObjectMapper`。

### 3.2 REST 与事件

- Dashboard 使用 `GET /api/chat/sessions/{sessionId}/tasks?userId=...`；iOS 使用设备鉴权的
  `GET /api/mobile/client/sessions/{sessionId}/tasks`。两端返回相同 envelope：
  `{sessionId, summary, tasks, generatedAt}`。
- Tool 写入后发布 `SessionTasksChangedEvent`；事务提交后的 listener 在现有 Session WebSocket 通道推送
  `session_tasks_snapshot`，payload 与 REST envelope 相同。
- REST/WS DTO 保持稳定：`taskId, subject, description, activeForm, status, owner, blocked, blockedBy,
  blocks, createdAt, updatedAt, version`。
- API/WS snapshot 包含 deleted 以支持可靠合并；UI 隐藏 deleted。客户端始终按 `taskId + version` upsert，
  不因旧/空 snapshot 缺项而删除本地项，从而抵抗 GET 与 WS 竞态。

## 4. Tool 实现

- 四个 Tool 的 schema 使用 `subject/description/activeForm/taskId`，不暴露内部列名。
- 入口修复 `id/task_id/active_form`；其余未知字段按现有兼容策略处理。
- `TaskUpdate` 先合并字段和依赖，再校验目标状态。
- 结果保持短小，不回显整个 Session；Create/Update 返回变更任务，List 返回压缩列表。
- 错误采用稳定 `errorCode/errorType/retryable/failedField/suggestedAction`。

## 5. Prompt 与动态上下文

### 5.1 分层

- Global stable：是否使用 Task、状态纪律、范围变化、Tool failure 不等于目标。
- Main Agent：普通追问/修改/新增/优先级/重开/继续/暂停矩阵。
- Tool Description：字段、状态、依赖、别名与错误说明。
- Dynamic Reminder：只包含数据库中的 open/blocked Task；标注可忽略与当前消息无关的任务。

### 5.2 Compact / restart

Task 不依赖历史 `tool_use/tool_result` 恢复。Reminder 每轮按 Session 查询数据库；Compact 后首次请求自然重新注入。
服务重启后数据库仍是事实源，不重放未完成 Tool call。

## 6. TodoWrite 删除策略

删除 runtime 类、Store、Source、Bean 和当前测试；Agent 工具注册/默认 tool IDs/Prompt 迁移到 Task 四工具。历史消息
仍按通用 ToolCall DTO 渲染，前端对 `TodoWrite` 保留只读卡片分支。旧枚举只在数据库/JSON 兼容需要时保留，
新增 Reminder 不再写 `PENDING_TODOS`。

## 7. Artifact 设计

### 7.1 Mode adapter

`PublishInteractiveArtifact` 在执行前解析为互斥的 `TemplateRequest` 或 `CustomFileRequest`，混传/漏传立即返回
validation error。Custom 解析后的真实路径必须仍在当前 run workspace 内。

### 7.2 Preflight

统一顺序：

1. 参数互斥与必填。
2. workspace 和 real path containment。
3. 文件类型、大小、UTF-8 与自包含 HTML。
4. 禁止能力/外部脚本/危险 URL 检查。
5. `state_schema` 大小、深度、节点和受支持关键字校验。
6. 发布存储。

失败映射为稳定错误码，例如 `ARTIFACT_MODE_CONFLICT`、`ARTIFACT_WORKSPACE_MISMATCH`、
`ARTIFACT_FILE_NOT_FOUND`、`ARTIFACT_FORBIDDEN_CAPABILITY`、`ARTIFACT_SCHEMA_INVALID`、
`ARTIFACT_IO_FAILURE`。成功仅返回引用和状态；Renderer 对 HTML/base64 设置硬上限。

### 7.3 通用 authoring guideline

不新增内容特定模板。提供一份通用交互页面规范，指导 Agent 根据用户需求决定信息架构、响应式布局、展开阅读和
来源跳转；现有 template 保持兼容并作为快速路径。

## 8. Dashboard / iOS

- 独立 Task store/view model，Chat 首屏不等待 Task 才可用。
- 初次 GET 后复用现有单 Session WebSocket；同一 taskId 按 version 合并，实时 snapshot 更新节流。
- Progress header 只汇总非 deleted；blocked 由依赖状态派生。
- iOS 使用可折叠 SwiftUI 区域；Dashboard 使用现有 Ant Design 卡片模式。
- Tool 卡片按 TaskCreate/Update 展示变化；未知/历史 TodoWrite 继续走兼容 renderer。

## 9. 测试策略

1. Migration：真实 PostgreSQL 验证约束、索引、Instant/JSONB 和回滚前向兼容。
2. Service/Tool：TDD 覆盖权限、状态、别名、依赖环、blocked、owner 唯一、乐观锁。
3. Prompt/Reminder：快照和 compact/restart 查询测试。
4. Artifact：template/custom 成功矩阵、路径逃逸、安全/schema 错误和结果大小。
5. Dashboard/iOS：DTO、加载降级、WS 合并、卡片与历史兼容。
6. Replay：脱敏 fixture + deterministic fake model/tool transcript，覆盖 PRD 十场景。
7. Full regression：Maven 全量、Dashboard test/build、iOS test/build、浏览器和模拟器/真机关键路径。

## 10. 风险与回滚

- 默认工具集合变化可能影响旧 Agent：数据库迁移只对内置 Main Assistant 做 `TodoWrite` 到四个 Task 工具的外科式替换，保留其余自定义 tool IDs；其他用户 Agent 不改写。
- Task 表新增不改历史消息 shape；可通过关闭新 Tool 注册回滚行为，数据保留。
- UI Task 加载独立失败，不影响 Chat 主路径。
- Artifact 原 template 路径保持不变；custom 预检可独立回滚。
