# TASK-COMPLETION-RECONCILIATION

日期：2026-09-06。状态：Full 开发及独立审查完成；233 项主线程回归与真实 legacy 恢复验证通过，已部署；用户已授权单独提交。

## 问题与证据

Session `37c7407d-3313-43e8-bdb4-fa421fb4dfcb` 跨重启恢复后正常交付最终回复，Session 为 idle，新 Trace 为 ok，但持久化 Task 仍为 in_progress。恢复期间未调用 TaskList/TaskUpdate。Task 状态原本只由显式 TaskUpdate 改变；恢复入口没有刷新当前数据库任务快照，文本终结分支也没有核对。

## 方案取舍

1. Session idle 时批量完成任务：不采用。阶段性回复、无关任务或等待后续输入均可能仍未完成。
2. 仅新增 prompt 提醒：可以补上下文，但模型仍可能漏做，不足以处理此次已观察到的终结遗漏。
3. 最新任务上下文 + 有界终结核对：采用。任务仍通过现有工具和权限/并发语义修改，不新增状态推断或schema。

## 实施范围

- 引擎每次启动通过 server 回调读取当前 Session 所有权校验后的 open Task 快照；任务文本限制长度并包装为低信任数据，固定操作要求置于边界外。
- 不修改原 user/history JSON，不追加伪造 user Continue；普通请求、重启恢复都可得到当前任务信息。
- 当前生产启用的 legacy 循环，在纯文本终结候选、仍有 open Task、未取消且还有循环预算时，至多触发一次任务状态核对继续。
- 模型仅核对本轮相关任务，确认已完成且满足验证要求时调用 TaskUpdate(completed)；阶段性、等待或无关任务保持原状态。已有交付全文只广播一次，不要求再次重复。
- 新用户排队消息优先，不因旧任务核对覆盖新用户意图；无 open Task 不增加调用；预算不足或取消不新增继续；不会无限阻止正常终结。
- durable 总开关目前关闭。本次其入口仅增加任务上下文，不改变其未完整验收的 frontier/intent/result 继续协议，不能用此次结果认证 durable 完成收尾。
- 原 Trace 的自动跨进程终结缺少可靠 ownership 关联，不在此次用 session/time 范围批量修复。原案如需数据补账，必须单独记录证据和人工/运维修复性质。

## 验证

- RED/GREEN：恢复输入包含最新任务快照；纯文本候选后完成任务核对；真实工具更新后结束。
- 阶段性/无关任务不被服务端自动完成；至多一次核对；无任务、取消、无预算没有新增调用。
- 原 user/history JSON 形状保持；既有 final 不重复广播；queued-user 优先；工具结果配对；Provider 周边回归。
- server callback 负例：无owner、跨用户、空任务、截断上限、异常读取、已完成任务排除。
- 主线程最终回归和 API/数据库验证，部署使用独立不可变 jar；等待旧 JVM 完全退出后才启动新 JVM。
- 原重启核查记录保留历史事实，新增修复后验证段，不把人工补账改写成原先自动成功。

验证明细见 [实际重启恢复核查](../../../operations/restart-recovery-session-20260906.md)。原历史 Session 状态未人工修改；新增独立会话验证相关任务 completed、等待任务 pending。

提交范围说明：单独提交基于尚无 durable 引擎的 ca3c9cb4，使用既有 MEMORY/STORED_DATA 边界并通过 178 项隔离测试；上述 durable-only 入口/测试说明属于未提交 History 工作区的集成验证，不代表此次提交新增 durable 能力。
