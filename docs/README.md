# SkillForge 文档

> 更新于：2026-07-19（任务恢复首期收窄为 kill-running MVP；新增 context overflow 最多三次 Full Compact 需求）
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
| **TASK-RESUME-ON-RESTART** | 首期收窄为单实例 kill-running MVP；待批准，尚未实现 | [需求包](requirements/backlog/TASK-RESUME-ON-RESTART/index.md) |
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
