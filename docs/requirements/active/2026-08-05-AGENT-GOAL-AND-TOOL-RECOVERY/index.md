# AGENT-GOAL-AND-TOOL-RECOVERY — 持久化 Task、目标连续性与 Artifact 恢复

> 状态：implemented / Full automated verified；待用户端浏览器与真机 dogfood
> 日期：2026-08-05
> 触发 Session：`7f623dc6-3610-46c2-9b95-6f3e0e1f4764`

## 摘要

把 Main Agent 的执行计划从内存态 `TodoWrite` 迁移到可持久化、可增量更新、可恢复的 Task 协议，并修复
Artifact 工具失败后 Agent 围绕工具错误偏离用户目标的问题。Dashboard 和 iOS 同步展示当前进度；用脱敏的
真实 Session 回放锁定多轮追加、修改、重开、压缩和重启行为。

## 本次交付

1. 单一模型协议：`TaskCreate`、`TaskUpdate`、`TaskGet`、`TaskList`；不再同时暴露 `TodoWrite`。
2. `t_session_task` 与依赖关系持久化，支持状态、owner、依赖、元数据、乐观锁和审计时间。
3. Task Reminder 仅渐进注入未完成/阻塞任务；Compact 和服务重启后从数据库恢复。
4. Global Prompt、Main Agent Prompt 和 Tool Description 分层治理 Task 生命周期。
5. `PublishInteractiveArtifact` 的 template/custom 契约、通用页面规范、预检和结构化错误。
6. Dashboard 与 iOS 的 Task 进度、当前动作、阻塞原因和历史 Todo 卡片兼容。
7. 真实问题 Session 的脱敏、确定性回放与跨层行为回归测试。
8. Dogfood Session `93fc675b-c8b9-4e5e-9ac4-439361590983` 的 P0 修正：Custom 发布改用相对
   `entry_file`，拒绝不可渲染 HTML，结构化返回恢复动作，并支持不可变的 Artifact 修订链。

## 明确延期

- Tool 重试预算和熔断。
- 固定“长文阅读”Artifact 模板。
- Schedule 真触发验收与 Skill-backed Schedule。
- 通用完成证据框架。
- 完整 Agent Team 自动协调、领取和调度。
- 自动 `STALE` / WorkPlan revision 状态机。

## 阶段与退出门

| 阶段 | 范围 | 退出门 |
| --- | --- | --- |
| P0 | 需求包、协议与迁移契约 | 无 TBD、无与延期项冲突的验收 |
| P1 | Task 持久化、四个 Tool、API/事件 | 工具、迁移、权限、并发和重启测试通过 |
| P2 | Prompt、Task Reminder、移除 TodoWrite runtime | Prompt 快照、Reminder、Compact 恢复测试通过 |
| P3 | Artifact 通用规范、预检和结构化错误 | template/custom 成功与错误矩阵通过 |
| P4 | Dashboard 与 iOS 展示 | 单测/构建通过，关键交互实机或模拟器/浏览器验证 |
| P5 | Session replay 与全量回归 | 回放场景全通过，Full Pipeline review 无阻塞项 |

## 交付验证

- 后端全量 Maven：4017 项执行，0 failure / 0 error，181 skipped；嵌入式 PostgreSQL 14 实际执行 V1–V189，依赖关系变更推动两侧 Task `@Version` 的集成测试通过。
- Dashboard：520 项通过，5 skipped，1 todo；production build 通过；Personal App 桥接覆盖伪造消息来源、危险协议和带凭据 URL 的拒绝测试。
- iOS：305 项单测与 1 项关键 XCUITest 通过；Release Simulator build 与 XcodeGen diff-check 通过；TaskUpdate 卡片覆盖 owner/metadata 增量文案。
- 运行态：Flyway V188/V189 成功；Task 表存在；Main Assistant 已从 `TodoWrite` 迁移到四个 Task 工具；认证后的 Task snapshot API 返回 200，未知 Session 返回结构化 404。
- Full Pipeline 第三轮独立审查为 `PASS_WITH_NOTES`，0 blocker / 0 major；审查留下的 3 个测试与呈现小项均已补齐并重新验证。
- Artifact P0 follow-up：聚焦测试 72/72；后端全量 3558 项执行、0 failure / 0 error、179 skipped；真实
  PostgreSQL 验证 V191 可重复更新派生操作约束，并由完整 Flyway 路径从 V187 升至 V191。
- 环境限制：当前自动化浏览器通道没有可用实例，因此未声称完成真实浏览器交互验收；用户端浏览器与 iOS 真机体验保留为 dogfood 门。

## 文档

1. [调研与问题证据](research-report.md)
2. [Prompt 删除审计](prompt-deletion-audit.md)
3. [MRD](mrd.md)
4. [PRD 与行为矩阵](prd.md)
5. [技术设计](tech-design.md)
6. [未来 Agent Team Task Graph](../../backlog/AGENT-TEAM-TASK-GRAPH/index.md)
