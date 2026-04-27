# SkillForge 交付索引

> 更新于：2026-04-27
> 目的：把“已完成事项 / 技术方案文档 / 完成日期 / commit / migration”集中到一个索引，`docs/todo.md` 只负责排期和状态。

---

## 维护规则

1. **新功能先有方案入口**：进入实施前，优先创建或复用一个 canonical 技术方案文档，命名为 `docs/design-<topic>.md`。评审稿、Plan A/B、Judge 记录只能作为 supporting docs。
2. **完成时同步两处**：任务完成后，同一个收尾 commit 必须更新 `docs/todo.md` 和本文档。`todo.md` 写一句完成摘要；本文档补完整索引行。
3. **索引行字段固定**：每个已完成交付至少记录 `完成日期`、`交付项`、`技术方案`、`辅助文档`、`验证/commit/migration`。
4. **无独立方案也要显式写清**：小修、热修或补丁没有技术方案时，`技术方案` 写“无独立方案”，并在备注里说明原因。
5. **Flyway migration 不回改**：已应用的 `Vxx__*.sql` 不再修改；后续 DB schema/data 变更新增下一个版本号，并在索引里记录 migration 编号。
6. **日期用完成日期**：表里的日期是代码/文档完成并验证的日期，不是方案创建日期。

---

## 已完成交付索引

| 完成日期 | 交付项 | 技术方案 | 辅助文档 | 验证 / commit / migration |
| --- | --- | --- | --- | --- |
| 2026-04-27 | Memory v2（PR-1 ~ PR-5）：`V29` schema、L0/L1 task-aware recall、增量抽取 cursor、embedding add-time dedup、状态机/淘汰/UI 收口 | [design-memory-v2.md](design-memory-v2.md) | [todo.md](todo.md) | `9f36b59` / `8330d32` / `86703ed` / `96676b9` + 当前 workspace PR-5 收尾；`V29`; `mvn -pl skillforge-server -am -Dtest=MemoryConsolidatorTest,MemoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 24/24；`skillforge-dashboard npm run build` |
| 2026-04-26 | P15-4 Agent 配置自省 + UpdateAgent：`GetAgentConfig`、`AgentDiscovery` 增强、带一次确认的 `UpdateAgent` | 无独立方案；属于 P11/P15 自省工具补齐，并复用 [design-sec2-hook-source-protection.md](design-sec2-hook-source-protection.md) 的 hook 来源隔离边界 | [todo.md](todo.md) | 本次提交；`mvn -pl skillforge-server -am -DskipTests compile`；`UpdateAgentToolTest` + `AgentLoopEngineInstallConfirmationTest` 16/16；server 重启确认工具注册 |
| 2026-04-26 | Agent 管理与内置模板 SQL 化收口：`CreateAgent` Tool、Code/Main Agent Flyway seed、Main Assistant leader config | 无独立方案；确认流复用 [design-install-confirmation-flow.md](design-install-confirmation-flow.md) 的 human confirmation pattern | [todo.md](todo.md) | `e22609c` / `3243759` / `d9d82a3` / `3542fa6`；`V26` / `V27` / `V28`；418 non-IT server tests；本地 DB 已到 v28 |
| 2026-04-26 | BUG-F Compact 摘要存储重构，修复 mixed user/tool_result 导致 OpenAI-compatible provider 400 | 无独立方案；关联 [design-p6-session-message-storage.md](design-p6-session-message-storage.md)、[design-p9-tool-result-compaction.md](design-p9-tool-result-compaction.md) | [bugs.md](bugs.md)、[todo.md](todo.md) | `e9b48f3`；370 unit tests；server 重启验证 |
| 2026-04-26 | Sprint 1：P9-7 token 估算、P3-1/P3-3 memory snapshot、P13-3 清理 | [design-p9-tool-result-compaction.md](design-p9-tool-result-compaction.md) 覆盖 P9 方向；其余为防腐补丁，无独立方案 | [todo.md](todo.md) | `621f417` / `f4773c3`；397 non-IT server tests |
| 2026-04-26 | Sprint 2 PR1/PR2：AgentDiscovery、name resolver、visibility、custom rule severity、GetTrace、GetSessionMessages | 无独立方案；属于 P11/P15 收窄实现 | [todo.md](todo.md) | `f48b61d`；413 non-IT server tests |
| 2026-04-25 | SEC-2 Hook Source Protection + Agent-authored Hook Binding V1 | [design-sec2-hook-source-protection.md](design-sec2-hook-source-protection.md) | [design-n3-lifecycle-hooks.md](design-n3-lifecycle-hooks.md) | `V24`; SEC-2 tests 27/27；frontend build |
| 2026-04-25 | thinking-mode-v1：per-agent thinkingMode/reasoningEffort/provider protocol family | 无独立方案；实现记录在 [todo.md](todo.md) | [todo.md](todo.md) | `55969db`；357 backend tests；13 frontend tests |
| 2026-04-24 | `Message.reasoning_content` 持久化，修复 thinking + tool_use 下一轮回放 | 无独立方案 | [todo.md](todo.md) | `V22`; 310 non-IT server tests |
| 2026-04-24 | 引擎稳定性：Compact breaker 误触、LLM stream 抗抖、provider HTTP client split | 无独立方案；问题记录归档在 [bugs.md](bugs.md) | [todo.md](todo.md) | `121e8dc`；364 non-IT server tests |
| 2026-04-22 | P13-2 Hook 编辑器重写 | [design-n3-lifecycle-hooks.md](design-n3-lifecycle-hooks.md) | [todo.md](todo.md) | 38 tests；browser e2e |
| 2026-04-22 | P13-5/7/8/10/11 follow-up batch | 无独立方案；前端/后端体验补丁 | [todo.md](todo.md) | 见 todo 完成记录 |
| 2026-04-22 | P9-1 Compactable 工具白名单 | [design-p9-tool-result-compaction.md](design-p9-tool-result-compaction.md) | [todo.md](todo.md) | 25 tests |
| 2026-04-22 | P9-3 Time-based 冷清理 | [design-p9-tool-result-compaction.md](design-p9-tool-result-compaction.md) | [todo.md](todo.md) | 12 tests |
| 2026-04-22 | P9-6 Session Memory 零 LLM 压缩 | [design-p9-tool-result-compaction.md](design-p9-tool-result-compaction.md) | [todo.md](todo.md) | 8 tests |
| 2026-04-21 | P8 记忆 LLM 提取 | 无独立方案；与 memory 系统相关 | [design-memory-vector-search.md](design-memory-vector-search.md)、[todo.md](todo.md) | 19 unit tests |
| 2026-04-21 | Session 批量删除 | 无独立方案 | [todo.md](todo.md) | Full Pipeline |
| 2026-04-21 | Session 来源渠道可视化 | 无独立方案；关联 channel gateway | [p2-channel-plan-b.md](p2-channel-plan-b.md)、[todo.md](todo.md) | 前后端实现记录见 todo |
| 2026-04-21 | Context breakdown API + 实时 token 面板 | 无独立方案 | [todo.md](todo.md) | Reviewer pass；前后端修复记录见 todo |
| 2026-04-21 | Session 详情 drawer 整体打磨 | 无独立方案；前端体验补丁 | [design-p5-frontend-optimization.md](design-p5-frontend-optimization.md)、[todo.md](todo.md) | 前端实现记录见 todo |
| 2026-04-21 | P7 飞书 WebSocket 长连接模式 | [design-feishu-websocket.md](design-feishu-websocket.md) | [p2-channel-plan-b.md](p2-channel-plan-b.md) | Feishu WS dispatcher / push manager tests；E2E 验证 |
| 2026-04-21 | P6 消息行存储重构 | [design-p6-session-message-storage.md](design-p6-session-message-storage.md) | [p6-rollout-playbook.md](p6-rollout-playbook.md) | `V18`; rollout toggle / checkpoint APIs |
| 2026-04-20 | P2 多平台消息网关：飞书 + Telegram + ChannelAdapter SPI | [p2-channel-plan-b.md](p2-channel-plan-b.md) | [p2-channel-plan-a.md](p2-channel-plan-a.md) | `V17`; Full Pipeline |
| 2026-04-20 | P1 Skill 自动生成 + 自进化 | [design-self-improve-pipeline.md](design-self-improve-pipeline.md) | [design-eval-methodology.md](design-eval-methodology.md) | Skill versioning / A-B / evolution run migrations |
| 2026-04-19 | P4 Code Agent | [design-p4-code-agent.md](design-p4-code-agent.md) | [design-sec2-hook-source-protection.md](design-sec2-hook-source-protection.md) | `V10` / `V11`; seed 后续统一迁到 `V26` |
| 2026-04-19 | P5 前端体验优化 | [design-p5-frontend-optimization.md](design-p5-frontend-optimization.md) | [design-references.md](design-references.md) | 前端 10 页面重构 |
| 2026-04-17 | N3 P2 Lifecycle Hook Method 体系 | [design-n3-lifecycle-hooks.md](design-n3-lifecycle-hooks.md) | [n3-p2-hook-method-review-a.md](n3-p2-hook-method-review-a.md), [n3-p2-hook-method-review-b.md](n3-p2-hook-method-review-b.md), [n3-p2-hook-method-judge.md](n3-p2-hook-method-judge.md) | 202 backend tests |
| 2026-04-17 | N3 P1 Lifecycle Hook 完善 | [design-n3-p1.md](design-n3-p1.md) | [n3-p1-lifecycle-hook-plan-a.md](n3-p1-lifecycle-hook-plan-a.md), [n3-p1-lifecycle-hook-plan-b.md](n3-p1-lifecycle-hook-plan-b.md), review/judge docs | 145 backend tests + browser e2e |
| 2026-04-17 | N3 P0 用户可配置 Lifecycle Hook | [design-n3-lifecycle-hooks.md](design-n3-lifecycle-hooks.md) | [session-state-and-ask-mode.md](session-state-and-ask-mode.md) | `V9`; 25 tests + browser e2e |
| 2026-04-17 | N2 Agent 行为规范层 | [design-n2-behavioral-rules.md](design-n2-behavioral-rules.md) | - | `V8` |
| 2026-04-16 | N1 Memory 向量检索 | [design-memory-vector-search.md](design-memory-vector-search.md) | - | `V7`; graceful fallback |
| 2026-04-16 | P2-6 Session → Scenario 转换 | [design-self-improve-pipeline.md](design-self-improve-pipeline.md) | [design-eval-methodology.md](design-eval-methodology.md) | `V5` |
| 2026-04-16 | P2-1~P2-5 Self-Improve Pipeline Phase 2 | [design-self-improve-pipeline.md](design-self-improve-pipeline.md) | [design-eval-methodology.md](design-eval-methodology.md) | `V4` |
| 2026-04-16 | #5/#6 Self-Improve Pipeline Phase 1 + 完整方案设计 | [design-self-improve-pipeline.md](design-self-improve-pipeline.md) | [design-eval-methodology.md](design-eval-methodology.md) | `V3`; 13 scenario JSON |
| 2026-04-16 | Collab-3 CollabRun WS 广播 | [subagent-async-dispatch.md](subagent-async-dispatch.md) | - | Teams page WS invalidate |
| 2026-04-15 | Compact-2 CompactionService 解锁 LLM 调用 | 无独立方案；后续由 P6/P9 文档承接 | [design-p6-session-message-storage.md](design-p6-session-message-storage.md), [design-p9-tool-result-compaction.md](design-p9-tool-result-compaction.md) | 3-phase split |
| 2026-04-15 | Context-1 动态 Context Window | 无独立方案 | [todo.md](todo.md) | ModelConfig lookup |
| 2026-04-15 | 认证鉴权 MVP、API 响应统一、前端测试基础、前端安全、后端 IT、DTO 现代化、Chat 交互、Dashboard 视觉、长列表虚拟滚动、TanStack Query、Chat.tsx 重构、Teams 页面、ObjectMapper 修复 | 多个早期小项，无独立方案 | [project-status-2026-04-15.md](project-status-2026-04-15.md), [todo.md](todo.md) | 早期交付批次 |

---

## 技术方案文档状态

| 文档 | 角色 | 当前状态 |
| --- | --- | --- |
| [design-self-improve-pipeline.md](design-self-improve-pipeline.md) | Self-Improve / Eval / Scenario 主方案 | 已交付主线，仍作为后续 eval/skill evolution 参考 |
| [design-eval-methodology.md](design-eval-methodology.md) | Eval 方法论 | 参考文档 |
| [design-memory-vector-search.md](design-memory-vector-search.md) | N1 Memory 向量检索 | 已交付 |
| [design-memory-v2.md](design-memory-v2.md) | Memory v2 写入 / 召回 / 淘汰一体化重构 | 已交付 |
| [design-n2-behavioral-rules.md](design-n2-behavioral-rules.md) | N2 行为规则 | 已交付 |
| [design-n3-lifecycle-hooks.md](design-n3-lifecycle-hooks.md) | N3 P0/P2 Lifecycle Hooks 总方案 | 已交付，后续 hook 相关继续复用 |
| [design-n3-p1.md](design-n3-p1.md) | N3 P1 最终实施方案 | 已交付 |
| [design-p4-code-agent.md](design-p4-code-agent.md) | P4 Code Agent | 已交付；seed 机制已由 Java initializer 修正为 Flyway |
| [design-p5-frontend-optimization.md](design-p5-frontend-optimization.md) | P5 前端体验 | 已交付，仍作为 UI 风格参考 |
| [design-p6-session-message-storage.md](design-p6-session-message-storage.md) | P6 消息行存储 | 已交付，仍是 session persistence 参考 |
| [design-p9-tool-result-compaction.md](design-p9-tool-result-compaction.md) | P9 Tool 输出裁剪 / 压缩 | 部分已交付；P9-2 仍在 Sprint 3 |
| [design-feishu-websocket.md](design-feishu-websocket.md) | P7 飞书 WS | 已交付 |
| [p2-channel-plan-b.md](p2-channel-plan-b.md) | P2 Channel Gateway 采纳方案 | 已交付 |
| [p2-channel-plan-a.md](p2-channel-plan-a.md) | P2 Channel Gateway 被替代方案 | 存档参考 |
| [design-sec2-hook-source-protection.md](design-sec2-hook-source-protection.md) | SEC-2 Hook 来源保护 | 已交付 |
| [design-install-confirmation-flow.md](design-install-confirmation-flow.md) | Install confirmation 授权流 | 已交付并被 CreateAgent confirmation 复用部分 pattern |
| [session-state-and-ask-mode.md](session-state-and-ask-mode.md) | 会话状态 / ask mode | 参考文档 |
| [subagent-async-dispatch.md](subagent-async-dispatch.md) | SubAgent 异步派发 | 已交付，仍是协作链路参考 |
| [browser-skill-login.md](browser-skill-login.md) | BrowserSkill 登录态 | 设计/参考文档，未在 todo 主表中单独追踪 |
| [design-references.md](design-references.md) | 前端视觉参考 | 参考文档 |
