# SkillForge 文档

> 更新于：2026-04-30
> Agent 规则：先读这里，再只打开当前任务链接到的文档。

编辑 docs 前，先读 [DOCS-GOVERNANCE.md](DOCS-GOVERNANCE.md)。

## 从这里开始

| 需求 | 阅读 |
| --- | --- |
| 当前执行队列 | [todo.md](todo.md) |
| 已完成交付事实 | [delivery-index.md](delivery-index.md) |
| 已知 bug 和 follow-up | [bugs.md](bugs.md) |
| 文档治理规则 | [DOCS-GOVERNANCE.md](DOCS-GOVERNANCE.md) |
| 重整前长版 ToDo | [references/legacy-todo-2026-04-28.md](references/legacy-todo-2026-04-28.md) |

## 当前需求

| ID | 标题 | 状态 | 需求包 | MRD | PRD | 技术方案 | 交付 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P12-PRE | Sprint 4 前置决策 | ready | [需求包](requirements/active/P12-preflight-decisions/index.md) | - | - | - | - |
| SKILL-LOAD | Skill Loader Tool | in-progress | [需求包](requirements/active/SKILL-LOAD-skill-loader-tool/index.md) | [MRD](requirements/active/SKILL-LOAD-skill-loader-tool/mrd.md) | [PRD](requirements/active/SKILL-LOAD-skill-loader-tool/prd.md) | [方案](requirements/active/SKILL-LOAD-skill-loader-tool/tech-design.md) | - |
| P1-D | Skill Root 与 Catalog 收口 | design-draft | [需求包](requirements/active/P1-D-skill-root-catalog-convergence/index.md) | [MRD](requirements/active/P1-D-skill-root-catalog-convergence/mrd.md) | [PRD](requirements/active/P1-D-skill-root-catalog-convergence/prd.md) | [方案](requirements/active/P1-D-skill-root-catalog-convergence/tech-design.md) | - |
| P9-2 | Tool Result 归档 | prd-ready | [需求包](requirements/active/P9-2-tool-result-archive/index.md) | [MRD](requirements/active/P9-2-tool-result-archive/mrd.md) | [PRD](requirements/active/P9-2-tool-result-archive/prd.md) | [方案](requirements/active/P9-2-tool-result-archive/tech-design.md) | - |
| P12 | 定时任务 MVP | prd-ready | [需求包](requirements/active/P12-scheduled-tasks/index.md) | [MRD](requirements/active/P12-scheduled-tasks/mrd.md) | [PRD](requirements/active/P12-scheduled-tasks/prd.md) | [方案](requirements/active/P12-scheduled-tasks/tech-design.md) | - |
| P9-4/P9-5 | Partial compact + post-compact 恢复 | prd-draft | [需求包](requirements/active/P9-4-P9-5-compaction-recovery/index.md) | [MRD](requirements/active/P9-4-P9-5-compaction-recovery/mrd.md) | [PRD](requirements/active/P9-4-P9-5-compaction-recovery/prd.md) | [方案](requirements/active/P9-4-P9-5-compaction-recovery/tech-design.md) | - |
| P10 | 聊天斜杠命令 | prd-ready | [需求包](requirements/active/P10-slash-commands/index.md) | [MRD](requirements/active/P10-slash-commands/mrd.md) | [PRD](requirements/active/P10-slash-commands/prd.md) | [方案](requirements/active/P10-slash-commands/tech-design.md) | - |

## Backlog 和暂缓

| ID | 标题 | 状态 | 文档 |
| --- | --- | --- | --- |
| SEC-1 | Channel 配置加密 | deferred | [需求包](requirements/backlog/SEC-1-channel-config-encryption/index.md) |
| BUG-G | 防御性 follow-up | deferred | [需求包](requirements/deferred/BUG-G-defensive-hardening/index.md) |

## 已交付方案

已交付需求优先归档到 `requirements/archive/`。历史 `design-*.md` 已合并进对应需求包的 `tech-design.md`；少量无法归属到单个需求的资料保留在 `references/` 或 `operations/`。交付事实以 [delivery-index.md](delivery-index.md) 为准。

| ID | 标题 | 需求包 | 技术方案 |
| --- | --- | --- | --- |
| P6 | 消息行存储 | [需求包](requirements/archive/2026-04-21-P6-session-message-storage/index.md) | [方案](requirements/archive/2026-04-21-P6-session-message-storage/tech-design.md) |
| SEC-2 | Hook Source Protection | [需求包](requirements/archive/2026-04-25-SEC-2-hook-source-protection/index.md) | [方案](requirements/archive/2026-04-25-SEC-2-hook-source-protection/tech-design.md) |
| Memory v2 | 写入 / 召回 / 淘汰 | [需求包](requirements/archive/2026-04-27-MEMORY-v2/index.md) | [方案](requirements/archive/2026-04-27-MEMORY-v2/tech-design.md) |
| OBS-1 | Session x Trace 合并详情视图 | [需求包](requirements/archive/2026-04-29-OBS-1-session-trace/index.md) | [方案](requirements/archive/2026-04-29-OBS-1-session-trace/tech-design.md) |
| CTX-1 | 三档触发接全量估算 + 阈值配置化 + 撞窗 retry | [需求包](requirements/archive/2026-04-30-CTX-1-context-token-accounting/index.md) | [方案](requirements/archive/2026-04-30-CTX-1-context-token-accounting/tech-design.md) |
| P5 | 前端体验优化 | [需求包](requirements/archive/2026-04-19-P5-frontend-optimization/index.md) | [方案](requirements/archive/2026-04-19-P5-frontend-optimization/tech-design.md) |
| P2 | Channel Gateway | [需求包](requirements/archive/2026-04-20-P2-channel-gateway/index.md) | [方案](requirements/archive/2026-04-20-P2-channel-gateway/tech-design.md) |
| P4 | Code Agent | [需求包](requirements/archive/2026-04-19-P4-code-agent/index.md) | [方案](requirements/archive/2026-04-19-P4-code-agent/tech-design.md) |
| P7 | 飞书 WebSocket | [需求包](requirements/archive/2026-04-21-P7-feishu-websocket/index.md) | [方案](requirements/archive/2026-04-21-P7-feishu-websocket/tech-design.md) |
| P9 | Tool 输出裁剪 / 压缩 | [需求包](requirements/archive/2026-04-22-P9-tool-result-compaction/index.md) | [方案](requirements/archive/2026-04-22-P9-tool-result-compaction/tech-design.md) |
| N1 | Memory 向量检索 | [需求包](requirements/archive/2026-04-16-N1-memory-vector-search/index.md) | [方案](requirements/archive/2026-04-16-N1-memory-vector-search/tech-design.md) |
| N2 | Agent 行为规范层 | [需求包](requirements/archive/2026-04-17-N2-behavioral-rules/index.md) | [方案](requirements/archive/2026-04-17-N2-behavioral-rules/tech-design.md) |
| N3 | Lifecycle Hooks | [需求包](requirements/archive/2026-04-17-N3-lifecycle-hooks/index.md) | [方案](requirements/archive/2026-04-17-N3-lifecycle-hooks/tech-design.md) |
| N3-P1 | Lifecycle Hook 增强 | [需求包](requirements/archive/2026-04-17-N3-P1-lifecycle-hook-enhancement/index.md) | [方案](requirements/archive/2026-04-17-N3-P1-lifecycle-hook-enhancement/tech-design.md) |
| P1 | Self-Improve Pipeline | [需求包](requirements/archive/2026-04-20-P1-self-improve-pipeline/index.md) | [方案](requirements/archive/2026-04-20-P1-self-improve-pipeline/tech-design.md) |
| INSTALL-CONFIRM | Install Confirmation 授权流 | [需求包](requirements/archive/2026-04-26-INSTALL-confirmation-flow/index.md) | [方案](requirements/archive/2026-04-26-INSTALL-confirmation-flow/tech-design.md) |
| SESSION-STATE | 会话状态 / ask mode | [需求包](requirements/archive/2026-04-17-SESSION-state-ask-mode/index.md) | [方案](requirements/archive/2026-04-17-SESSION-state-ask-mode/tech-design.md) |
| SUBAGENT | SubAgent 异步派发 | [需求包](requirements/archive/2026-04-16-SUBAGENT-async-dispatch/index.md) | [方案](requirements/archive/2026-04-16-SUBAGENT-async-dispatch/tech-design.md) |
| BROWSER-LOGIN | BrowserSkill 登录态 | [需求包](requirements/archive/2026-04-17-BROWSER-skill-login/index.md) | [方案](requirements/archive/2026-04-17-BROWSER-skill-login/tech-design.md) |

## 参考与运维

| 需求 | 阅读 |
| --- | --- |
| LLM provider 踩坑 | [LLM provider 踩坑录](references/llm-provider-quirks.md) |
| Dashboard 视觉参考 | [前端视觉参考](references/design-references.md) |
| Eval 方法论 | [Eval 方法论](references/design-eval-methodology.md) |
| P6 灰度手册 | [operations/p6-rollout-playbook.md](operations/p6-rollout-playbook.md) |

## 归档规则

- `requirements/active/`：当前或近期需求。
- `requirements/backlog/`：未来可能做，但未排期。
- `requirements/deferred/`：V2 或明确暂缓。
- `requirements/archive/`：已完成需求。
- `references/`：长期参考资料。
- `operations/`：运维手册和脚本。
