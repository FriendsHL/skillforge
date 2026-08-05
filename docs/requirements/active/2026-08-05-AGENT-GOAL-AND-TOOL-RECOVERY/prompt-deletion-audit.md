# Prompt 删除审计

> 审计范围：当前 Global Prompt、Main Assistant Prompt、Behavior Rules 与 `cc044baa` 前后差异
> 对照：本地 Claude Code 2.1.220
> 结论：`IMPLEMENTED_AND_DEPLOYED`；Todo 生命周期与 V185 Behavior Rules 回归均已修复，V188 已在本地运行库验证

## 1. Blocker：V185 清空 Main Assistant 内置规则

`V28__main_assistant_leader_config.sql` 原本为 Main Assistant 配置 16 条 builtin Behavior Rules。提交
`cc044baa` 新增的 `V185__streamline_main_assistant_prompt.sql` 将其无条件改成：

```json
{"builtinRuleIds": [], "customRules": [...]}
```

当前 Global Prompt 只等价承载了其中约 6 条。以下工程语义没有等价覆盖：

- 项目工作区边界；
- Shell、SQL、文件输入清洗；
- 最小范围修改；
- 禁止生产 mock、占位实现和 TODO 桩；
- 修改后测试；
- 显式声明假设；
- 简洁优先，拒绝投机性抽象；
- 只清理由本次修改产生的孤儿代码；
- 将任务转换成可验证目标。

这与 Context Governance 保留独立 Behavior Rules 层的设计不一致，也可能解释 Main Assistant 近期工程执行质量下降。

### 恢复决策与实施

原 16 条规则按“是否已有等价承载”拆分：

| 处理 | 规则 | 原因 |
| --- | --- | --- |
| 恢复到 Behavior Rules | `sandbox-file-scope`、`validate-input`、`minimal-change`、`no-mock-in-prod`、`test-after-change`、`state-assumptions-explicit`、`simplicity-first-no-speculation`、`clean-only-own-orphans`、`goal-driven-verify-loop` | 当前 Global/Main Prompt 没有等价语义，且只属于 Main Agent 工程执行治理 |
| 不恢复到 Behavior Rules | `confirm-destructive-ops`、`no-secret-in-output`、`no-force-push-main`、`read-before-edit`、`prefer-edit-over-write`、`explain-before-act`、`ask-when-ambiguous` | 已由 Global Prompt 或 Main Prompt 等价覆盖，重复注入只会增加上下文 |

实施方式：

1. 保留已经执行的 V185，新增条件式 `V188__restore_main_assistant_prompt_governance.sql`。
2. Behavior Rules 与 Main Prompt 分别判断 V185 默认形状；用户只自定义其中一项时，不阻碍另一项修复。
3. Main Prompt 恢复“最新指令优先、未被后续反馈改变的决定不重复推导、权衡给推荐”。
4. 三个 PostgreSQL 迁移场景锁定默认恢复、自定义规则保留和自定义 Prompt 保留。

## 2. 已修复：Todo 生命周期被 Task* 调整误删

审计开始时，未提交改动用 TaskCreate/TaskUpdate 分工替换了原有“需求变化或发现必要的新步骤时及时更新”，问题
Session 的真实请求也已使用缺失该规则的 Prompt。

P0-A 当前工作区已经：

- 恢复新指令先更新、用户反馈后重开、范围新增时追加、失效任务移除；
- 将模型侧 TodoWrite 收口为 `content + activeForm + status` 完整替换协议；
- 停止把全部 completed 的旧列表作为 `PENDING_TODOS` 注入；
- 移除未完成的 TaskCreate/TaskUpdate/TaskList 运行时实现和注册。

## 3. Warnings

### W1 平台危害范围过度精简 — 已恢复

旧 Prompt 对恶意代码、供应链攻击、批量攻击/DoS、逃避检测和双用途授权语境有明确边界；当前仅剩通用安全描述。
Global Prompt 已恢复一条紧凑的平台能力边界，同时继续依赖服务端硬控制。

### W2 已确认决策与推荐语义被删除 — 已恢复

Main Agent Prompt 已使用不会阻止用户修正的表述：

> 遵循用户最新指令；已确认且未被后续反馈改变的决定不重复推导。需要权衡时给出明确推荐。

### W3 删除/覆盖前的目标核对被弱化 — 已恢复

“破坏性动作先确认”不能替代“先解析并核对实际目标”。Global 权限边界已恢复该紧凑规则。

### W4 多 Provider 身份约束被删除 — 已恢复

Global 身份段已补充：运行模型由 Agent 配置决定，不假设固定 Provider、模型身份或知识截止时间。

### W5 缺少 Prompt 删除回归门 — 部分完成

本次已增加 Global Prompt 语义测试及 V188 PostgreSQL 迁移测试，包括额外顶层字段与非法 JSON 的保护场景。独立复审结论为 PASS；本地运行库已确认 `flyway_v188=true`，Main Assistant 的规则与决策文案均已恢复。后续 Prompt 精简仍必须附“删除项 → 等价承载位置”表。

## 4. 无需恢复

- 项目模块、端口和固定构建命令：由项目 Skill 或文档按需加载。
- AnySearch 长路由手册：由动态能力说明和 Tool Description 承担。
- “先说再做”的长示例：Global Prompt 已保留核心语义。
- Read/Grep/Glob/Edit/Bash 边界：当前 Global Prompt 更完整。
- Bash 长手册从 Tool Description 移到 Global Guidelines：符合静态决策与动态调用契约分层。

## 5. Claude Code 对照边界

- Claude Code 2.1.220 的 canonical TodoWrite 使用 `content + activeForm + status` 和整表替换。
- 详细“收到新指令立即建任务”等文本主要属于结构化 Task 工具指导，不应声称全部来自 TodoWrite Description。
- TodoWrite 动态 Reminder 强调清理 stale、与当前工作不匹配的列表。
- SkillForge 的合理分层是：Global Prompt 管生命周期；Tool Description/Schema 管调用契约；Reminder 管当前未完成状态和 stale 核对。
