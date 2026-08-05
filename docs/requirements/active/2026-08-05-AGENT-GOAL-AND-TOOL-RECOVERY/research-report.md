# 调研报告 — Claude Code TodoWrite 与 SkillForge 回归证据

## 1. 调研对象

- 本机 Claude Code：`2.1.220`，Mach-O arm64，构建时间 2026-07-24。
- 本机历史版本：`2.1.187`、`2.1.191`、`2.1.195`、`2.1.220`。
- SkillForge Git 历史、当前工作区、问题 Session 的真实 LLM request blob 与消息记录。

Claude Code 是编译产物，本报告只把可执行文件中的静态字符串视为本机版本证据；不声称这些字符串在所有
Feature Flag、模型或 Surface 下都会同时发送。

## 2. Claude Code 的三层任务治理

### 2.1 System Prompt：短而稳定的通用要求

本机静态 System 片段要求模型用任务工具拆解和跟踪工作，并在每个任务完成后立即更新，不要把多个完成状态
攒到最后一起更新。

它不在 System Prompt 中重复完整 Schema，而把详细决策规则留在 Tool Description。

### 2.2 TodoWrite 契约与任务治理文本

本机 2.1.220 可确认 TodoWrite 的 canonical 契约包含：

- 开始前设为 `in_progress`，同一时间一个进行项。
- 完成后立即标记，不批量延迟更新。
- 只有真正完成才能 `completed`；错误、阻塞、测试失败或部分实现时不能完成。
- 失去相关性的任务应从当前列表移除。
- 每次发送完整列表，替换旧列表。

本机二进制还包含“三个以上步骤、复杂任务、用户一次给出多个任务、收到新指令后立即纳入任务”等详细任务治理
文本；其中一部分属于结构化 Task 工具指导，不能把这些长说明全部归因于 TodoWrite Tool Description。SkillForge
采用这些语义时，将它们视为平台任务生命周期规则，而不是声称逐句复制 Claude Code 的 TodoWrite 手册。

其外部字段是：

```json
{
  "todos": [
    {
      "content": "Run tests",
      "activeForm": "Running tests",
      "status": "pending | in_progress | completed"
    }
  ]
}
```

### 2.3 Dynamic Reminder：提醒列表可能过期

Claude Code 的静态字符串还包含温和 Reminder：TodoWrite 长时间未使用时，提醒模型在任务仍适合跟踪时更新，
并清理已经 stale、与当前工作不匹配的列表。该 Reminder 明确允许“不适用时忽略”，而不是把任何非空列表都
标成 Pending 状态永久注入。

上述任务治理文本和“清理 stale list”在本机 2.1.187、2.1.191、2.1.195、2.1.220 中均存在，
不是 2.1.220 临时新增行为。

## 3. TodoWrite 与 Task* 不是同一协议

Claude Code 2.1.220 同时包含另一套结构化 Task 工具：

- TaskCreate：`subject`、`description`、可选 `activeForm`，服务端生成 ID。
- TaskUpdate：使用 `taskId`，可更新 subject/description/status/dependencies/owner/metadata。
- TaskGet/TaskList：面向带依赖和 owner 的任务图。

因此：

```text
TodoWrite.content != TaskCreate.subject
TodoWrite full replacement != TaskUpdate incremental graph mutation
```

SkillForge 在 P0-A 前的未提交改动把 TodoWrite 字段也改成 `subject`，同时让 Main Agent 优先使用简化版 TaskCreate /
TaskUpdate / TaskList，但这些 Task 工具又没有 Claude Code 的 TaskGet、`taskId`、依赖和 owner 语义。这是接口
表面相似、实际契约不完整的问题。

## 4. SkillForge 回归证据

### 4.1 丢失的 Prompt 规则

提交 `cc044baa` 的 Global Prompt 原本包含：

```text
每次调用都提交完整任务列表，由新列表替换旧列表；需求变化或发现必要的新步骤时及时更新。
```

P0-A 前的 Task 工具调整用工具分工文本替换了这行，没有保留“需求变化时更新”的生命周期要求。

问题 Session 在 2026-08-04 的真实 request blob 中已经使用缺失该规则的新 Prompt，因此这是运行时事实，
不是仅由 Git diff 推测。

### 4.2 Completed Todo 的错误锚定

问题 Session 从消息 seq 126 到 247 多次收到用户修正，包括“不是这个意思”“还是不对”“需要播客而不是热点”。
每次用户消息前仍注入相同四项全部 Completed 的 Todo 列表。

原因是 `TodoListSource.shouldEmit()` 只判断列表非空，`render()` 永远包含 Completed，并统一使用
`PENDING_TODOS` reason。

### 4.3 不是单一 Publish 故障

- PublishInteractiveArtifact template：6 次调用，6 次成功。
- custom file：18 次调用，2 次成功，16 次失败。
- 首次澄清“播客内容”前已经发生 25 次工具调用，最初目标理解偏差早于 Publish 失败。

结论：Publish custom failure 是需求漂移的强放大器，但旧 Todo 锚定和缺少目标版本才使漂移持续。

## 5. 对 SkillForge 的直接启示

1. 不能依赖 Claude 模型训练记忆；问题 Session 使用的是 `deepseek-v4-pro`。
2. 外部工具协议应尽量贴合模型熟悉的契约；SkillForge 最终选择完整 Task 四工具，而不是继续维护 TodoWrite 与
   简化 Task 两套重叠协议。
3. Prompt 负责决策规则，Tool Schema 负责字段形状，Reminder 负责当前状态和 stale 信号；三者不能互相替代。
4. Task 是工作状态，不是用户目标的权威来源；用户最新消息永远可以重开不满足原验收的 Completed Task。
5. `max_loops=100` 会放大重复失败；错误预算仍有价值，但按用户决定延期，不进入本次交付。

## 6. 2026-08-05 最终协议决策

进一步对比 Claude Code 当前 Task 协议和 SkillForge 的恢复需求后，本包不再继续 P0-A 的 TodoWrite 方向：

- Main Agent 只暴露 `TaskCreate`、`TaskUpdate`、`TaskGet`、`TaskList`。
- Task 使用数据库持久化，支持稳定 `taskId`、增量更新、owner、依赖、Compact 和重启恢复。
- TodoWrite runtime 删除；历史消息只读兼容，不迁移为 Task。
- 不引入自动 stale、WorkPlan revision 或新的目标状态机；普通追问不更新 Task。

问题 Session 已生成脱敏 fixture：
`skillforge-server/src/test/resources/replay/session-7f623dc6-goal-drift-sanitized.json`。原始 248 条消息只抽取
用户意图类型、Tool 名称、成功/失败和稳定错误分类；本地路径、外部 URL、Tool 参数值、HTML 和 Provider 输出均
未进入仓库。观测汇总为：`PublishInteractiveArtifact` 24 次（16 失败、8 成功）、`TodoWrite` 6 次、`Bash`
22 次、`UpdateScheduledTask` 7 次。该 fixture 用于锁定“用户反复修正但 Completed 计划持续注入、Artifact 失败
放大需求漂移”的回归场景。
