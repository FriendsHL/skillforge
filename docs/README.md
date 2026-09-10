# SkillForge 文档

> 更新于：2026-09-10（Session History Recovery 专项真实 E2E 通过；两项 durable 问题暂停，Full gate 未关闭）
> Agent 规则：先读这里，再只打开当前任务链接到的文档。

## 从这里开始

| 目的 | 阅读 |
| --- | --- |
| 当前执行队列 | [todo.md](todo.md) |
| 已完成交付事实 | [delivery-index.md](delivery-index.md) |
| 已知 bug 和 follow-up | [bugs.md](bugs.md) |
| 文档治理 | [DOCS-GOVERNANCE.md](DOCS-GOVERNANCE.md) |

## 当前需求

| ID | 标题 | 状态 | 需求包 |
| --- | --- | --- | --- |
| **BAILIAN-TOKEN-PLAN** | 百炼五模型与系统 Agent 模型编辑 | 已实现、浏览器验证；上游生成受周额度限制 | [需求包](requirements/active/2026-09-05-BAILIAN-TOKEN-PLAN/index.md) |
| **SESSION-HISTORY-RECOVERY** | Compact 后的历史事实检索、精确展开与 Session 连续性 | Full / 已实现、专项真实 E2E 通过；完整门禁开放 | [需求包](requirements/active/2026-09-02-SESSION-HISTORY-RECOVERY/index.md) |
| **GOAL-ALIGNED-AGENT-FACTORY** | 目标对齐的个人助手能力工厂 | Design proposed / Full；P0a 已交付，P0b/P1 及后续方案待产品讨论 | [需求包](requirements/active/2026-08-26-GOAL-ALIGNED-AGENT-FACTORY/index.md) |
| **GOAL-BRIEF-P0A** | 对话内轻量目标确认 | implemented / Full automated + browser verified | [需求包](requirements/active/2026-08-26-GOAL-BRIEF-P0A/index.md) |
| **AGENT-TEAM-TASK-GRAPH** | Team 共享 Task、并发领取、租约、解锁与恢复 | Phase 1 implemented / Full automated + real Team dogfood verified | [需求包](requirements/active/2026-08-12-AGENT-TEAM-TASK-GRAPH/index.md) |
| **AGENT-GOAL-AND-TOOL-RECOVERY** | 持久化 Task、目标连续性与 Artifact 恢复 | implemented / Full automated + Dashboard browser verified；待 iOS 真机 dogfood | [需求包](requirements/active/2026-08-05-AGENT-GOAL-AND-TOOL-RECOVERY/index.md) |
| **HARNESS-BENCHMARK-COMPARISON** | SkillForge 与 Claude Code/Codex/OpenHands 的同模型公开 Benchmark 横向评测 | 需求包完成；等待 Context Governance P2–P6 | [需求包](requirements/active/2026-07-29-HARNESS-BENCHMARK-COMPARISON/index.md) |
| **AGENT-CONTEXT-GOVERNANCE** | System Prompt、Instruction、Reminder、ToolSearch、Memory 与 Compact 连续性治理 | P0–P2、P2.5、P4–P6 已交付；P3 跳过，P7–P9 待分期实施 | [需求包](requirements/active/2026-07-26-AGENT-CONTEXT-GOVERNANCE/index.md) |
| **REALTIME-VOICE-CONVERSATION** | iOS 实时语音对话、打断、字幕与 Agent Tool 编排 | Design Proposed / Full；Ark first，Qwen-compatible | [需求包](requirements/active/2026-07-23-REALTIME-VOICE-CONVERSATION/index.md) |
| **MULTIMODAL-MEDIA-RUNTIME** | 图片、音频、视频生成/理解、管理端与 iOS 统一呈现 | P0/P1 complete / Full；Main Agent Seedream 真生图与跨端 `image_ref` 闭环完成，视频当前不可新增接入 | [需求包](requirements/active/2026-07-22-MULTIMODAL-MEDIA-RUNTIME/index.md) |
| **IOS-CHAT-CONTROL-AGENTS-EXPERIENCE** | Chat 滚动、身份 Header、Tool 卡片与 Control/Agents 信息架构 | P0 code complete / focused 31/31 + stability 10/10；final Full gate BLOCKED_ENV；P1 Proposed 待确认 | [需求包](requirements/active/2026-07-18-IOS-CHAT-CONTROL-AGENTS-EXPERIENCE/index.md) |
| **IOS-AGENT-SESSION-LIVE-FOLLOW** | Agent/Session 导航、流式智能跟随、运行指示器与新消息提醒 | Phase 1 已实现并验证；Phase 2 Full 提醒能力待设计 | [需求包](requirements/active/2026-07-18-IOS-AGENT-SESSION-LIVE-FOLLOW/index.md) |
| **IOS-LOCAL-SIGNING-STABILITY** | 本机 Apple Team / XcodeGen 签名选择持久化 | implemented / Full automated verified；待 iPhone 重连做 GUI no-prompt 验收 | [需求包](requirements/active/2026-07-18-IOS-LOCAL-SIGNING-STABILITY/index.md) |
| **IOS-CHAT-MARKDOWN-VISUAL-POLISH** | 蓝色用户 Query 与 Markdown 阅读体验 | implemented / Mid automated verified（337/337）；待真机视觉/VoiceOver | [需求包](requirements/active/2026-07-18-IOS-CHAT-MARKDOWN-VISUAL-POLISH/index.md) |
| **IOS-AGENT-FILE-DELIVERY** | Agent 向 App 用户交付生成文件 | implemented / P1；待真实 Agent + iOS 真机验收 | [需求包](requirements/active/2026-07-16-IOS-AGENT-FILE-DELIVERY/index.md) |
| **IOS-TASK-COMPLETION-PUSH** | Task 结束后的 APNs 系统通知 | implemented / P1；待真实 APNs 到机验收 | [需求包](requirements/active/2026-07-16-IOS-TASK-COMPLETION-PUSH/index.md) |
| **IOS-INTERACTIVE-ARTIFACTS** | 手机端 Personal App / Interactive Artifact | implemented / simulator-verified；待真机 dogfood | [需求包](requirements/active/2026-07-16-IOS-INTERACTIVE-ARTIFACTS/index.md) |
| **IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH** | iOS 失败恢复、Retry 与 Personal App 体验收口 | implemented / Full pipeline verified（2026-07-17） | [需求包](requirements/active/2026-07-17-IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH/index.md) |
| **IOS-PERSONAL-APP-LIBRARY** | 跨 Session 的 Personal App 统一入口 | implemented / Full automated verified；紧凑卡片与同构 loading skeleton 已补齐；待用户真机 dogfood | [需求包](requirements/active/2026-07-17-IOS-PERSONAL-APP-LIBRARY/index.md) |
| **IOS-AGENT-FIRST-CHAT** | 以 Agent 结果为中心的手机聊天页 | implemented / Full automated verified（337/337）；待真机视觉/VoiceOver | [需求包](requirements/active/2026-07-17-IOS-AGENT-FIRST-CHAT/index.md) |
| **IOS-PROTOTYPE-APP-PARITY** | iOS 原型与真实 App 一致性 | Current 已同步至柔和蓝 Query 与 Markdown 语义块 | [需求包](requirements/active/2026-07-16-IOS-PROTOTYPE-APP-PARITY/index.md) |
| **IOS-ASSISTANT-COMPANION** | iOS V1 core 与发布收口 | core implemented；TestFlight/真机门开放 | [需求包](requirements/active/2026-07-09-IOS-ASSISTANT-COMPANION/index.md) |
| **AUTOEVOLVING-MASTER** | autoEvolving V2–V5 | V1 done；V2–V5 待启动 | [需求包](requirements/active/2026-05-28-AUTOEVOLVING-MASTER/index.md) |
| **AUTOEVOLVE-CLOSE-LOOP** | 阶段 B / G3 / P3 | 部分交付 | [需求包](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md) |
| **AUTORESEARCH-OPTIMIZATION** | 自动外部调研与人审入 backlog | prd-draft | [需求包](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/index.md) |
| **ACP-EXTERNAL-AGENT** | cc/codex ACP 编排后续 | 主闭环已交付；小项开放 | [需求包](requirements/active/2026-06-19-ACP-EXTERNAL-AGENT/index.md) |

## 近期优先 backlog

| ID | 状态 | 需求包 |
| --- | --- | --- |
| **AG-UI-COMPATIBILITY** | backlog-disabled；暂不启用、暂不开发 | [需求包](requirements/backlog/AG-UI-COMPATIBILITY/index.md) |
| **TASK-RESUME-ON-RESTART** | 单实例 kill-running MVP；Root/SubAgent 边界恢复已实现，Workflow/真 kill 验收待完成 | [需求包](requirements/backlog/TASK-RESUME-ON-RESTART/index.md) |
| **CONTEXT-OVERFLOW-TRIPLE-FULL-COMPACT** | context overflow 最多三次 Full Compact；Core 已实现并通过 Core+Server 回归 | [需求包](requirements/backlog/CONTEXT-OVERFLOW-TRIPLE-FULL-COMPACT/index.md) |
| **WF-CONCURRENT-PIPELINE** | AUTOEVOLVING V2(d) | [需求包](requirements/backlog/WF-CONCURRENT-PIPELINE/index.md) |
| **OUTCOMES-RUBRIC-FOUNDATION** | 等用户升 active | [需求包](requirements/backlog/OUTCOMES-RUBRIC-FOUNDATION/index.md) |
| **SEC-1** | channel 配置加密，暂缓 | [需求包](requirements/backlog/SEC-1-channel-config-encryption/index.md) |

其余候选见 `requirements/backlog/`；明确暂缓见 `requirements/deferred/`。

## 本次状态校准

- 已归档：COMPACT-IDEMPOTENCY-BOUNDARY-FIX、EVOLVE-JUDGE-GROUNDING、EVOLVE-CANDIDATE-GROUNDING、WECHAT-CHANNEL、PERSONAL-WORKSPACE-BROWSER、ANNOTATOR-BEHAVIOR-SIGNALS、OPT-REPORT-V1。
- 移入 deferred：DESKTOP-MACOS-PACKAGE；已有基础实现，但正式安装包与发布验收未完成。
- iOS V1 中“Agent 产物交付”的代码基础保留，但因真实 E2E 验收缺失且用户报告不可用，重新拆成独立 P1 active。
- APNs 从 iOS V1 大包拆为独立 P1 active；当前只能前台更新，后台/关闭时没有系统推送。

## 已交付与历史

已交付需求统一在 [`requirements/archive/`](requirements/archive/)；完成日期、commit、migration 和验证证据以 [delivery-index.md](delivery-index.md) 为准。不要因为目录仍有历史 `TODO` 或未勾选的发布门，就把已经交付的主体重新当作 active。

## 参考与运维

| 主题 | 文档 |
| --- | --- |
| LLM provider 踩坑 | [references/llm-provider-quirks.md](references/llm-provider-quirks.md) |
| Dashboard 视觉参考 | [references/design-references.md](references/design-references.md) |
| Eval 方法论 | [references/design-eval-methodology.md](references/design-eval-methodology.md) |
| P6 灰度 | [operations/p6-rollout-playbook.md](operations/p6-rollout-playbook.md) |

## 目录规则

- `requirements/active/`：当前或近期会进入设计/实现。
- `requirements/backlog/`：值得保留但未排期。
- `requirements/deferred/`：明确暂缓。
- `requirements/archive/`：已交付需求。
- `references/`：长期参考。
- `operations/`：运维手册与脚本。
