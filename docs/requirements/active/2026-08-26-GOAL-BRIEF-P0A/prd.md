# GOAL-BRIEF-P0A PRD

---
id: GOAL-BRIEF-P0A
status: implemented
priority: P1
risk: Full
created: 2026-08-26
updated: 2026-08-30
---

## 目标

- 让主 Agent 在复杂任务开始前用自然语言确认最关键的目标边界。
- 保持同一 Chat、同一主 Agent 和正常输入框，不引入 Wizard 或阻塞状态。
- 用现有 Session Task metadata 验证产品价值，不提前建设正式 Goal Contract。

## 触发条件

满足任一条件时主 Agent 必须先创建 Goal Brief：

- 长期或明确希望以后复用的目标。
- 多步骤端到端交付。
- 需要寻找、创建或启用新能力。
- 需要扩大权限、预算、数据外发或执行高风险动作。

普通问答、解释、简单改写、已有能力可安全直接完成的一次性任务不得触发。

## Goal Brief 内容

用户只需要理解四项：

```text
outcome               想要的结果
representativeExample 一个代表性交付物或例子
antiGoals             绝不能发生的事
askBefore              哪些动作必须先询问
```

metadata 同时记录：

```text
kind: goal_brief
schemaVersion: 1
proposalStatus: proposed | revised
fieldSources: USER_STATED | USER_CONFIRMED | SYSTEM_INFERRED | UNKNOWN | CONFLICTING
sourceQuote: 当前用户原话的有界摘录
```

`proposalStatus` 和 `fieldSources` 都是模型提议，不代表服务端确认。

## 用户流程

1. 用户向主 Agent 提出任务。
2. 主 Agent 必要时追问最多 1–2 个具体问题。
3. 主 Agent 创建带 `goal_brief` metadata 的 Session Task。
4. Chat 的 Task 区域显示“我理解的目标”卡。
5. 主 Agent 在当前回复停止能力变更，等待用户选择或自由文本反馈。
6. Goal Brief 是持续可见的目标摘要，不显示为“等待批准”；用户选择：
   - 确认并继续。
   - 需要修改。
   - 先只做这一次。
7. 选择结果作为普通用户消息发送，输入框始终可用；旧卡不会假装变成权威 `approved` 状态。
8. 主 Agent 根据最新用户消息继续；目标改变时创建新的 completed Goal Brief 记录，UI 只展示最新一张。

## 决策与安全

- Goal Brief 不得替代 CreateAgent、Tool、外部代码、权限或发布确认。
- 创建或修订 Goal Brief 后，主 Agent 不得在同一轮继续搜索、安装、导入或启用新能力，也不得创建、更新或调度常驻 Agent。
- 用户选择“先只做这一次”时，主 Agent 使用现有能力完成一次性任务，不创建持久能力。
- `SYSTEM_INFERRED/UNKNOWN/CONFLICTING` 不得被主 Agent 当作用户授权。
- 用户消息与 Goal Brief 冲突时，以用户消息为准。
- UI 不显示“已批准”“Active Goal”等权威措辞，只显示“提议”或“我理解的目标”。
- Goal Brief Task 创建后由服务端确定性记为 `completed`，并从普通 Task 进度/统计中排除。

## 验收标准

- [x] 普通简单任务不显示 Goal Brief。
- [x] `metadata.kind=goal_brief` 的 Task 显示四项紧凑目标卡。
- [x] 无效/缺字段 metadata 安全回退为普通 Task，不导致 Chat 崩溃。
- [x] 三个操作按钮分别发送普通用户消息，不直接修改 metadata、权限或批准状态。
- [x] “需要修改”发送有限定语义的普通消息，请助手继续澄清；不需要外部 prefill，也不禁用 Composer。
- [x] Session 切换、刷新和 Task WS 更新后卡片能从现有 Task snapshot 恢复。
- [x] Goal Brief Task 的普通 Task 状态与依赖行为保持现有语义。
- [x] Goal Brief Task 被确定性保存为 completed，不计入普通 Task summary；多个版本只显示最新卡。
- [x] Main Assistant Prompt 明确触发条件、四项内容和非授权边界。
- [x] 一个 Coding 长任务和一个非 Coding 长任务完成浏览器 dogfood。

## 非目标

- 正式不可变 Goal Contract、跨 Session Goal、Acceptance Set 和 Eval 绑定。
- 能力缺口搜索、常驻 Agent 自动创建、Canary 或自进化。
- 新 ContentBlock、Message 字段、Session 状态或数据库表。
- 独立 Goal 管理页或无限画布。
