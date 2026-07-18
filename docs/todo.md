# SkillForge ToDo

> 更新于：2026-07-19（任务恢复首期收窄为 kill-running MVP；新增 context overflow 最多三次 Full Compact）
> 规则：这里只放当前执行状态；范围与方案见需求包，交付事实见 [delivery-index.md](delivery-index.md)。

## 当前队列

| 顺序 | ID | 标题 | 模式 | 状态 | 优先级 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | **IOS-CHAT-CONTROL-AGENTS-EXPERIENCE** | Chat 滚动、身份 Header、Tool 卡片与 Control/Agents 信息架构 | Full | P0 code complete / focused 31/31 + stability 10/10；final Full gate BLOCKED_ENV；P1 Proposed 四屏待确认 | P0/P1 | 恢复 CoreSimulator host 后补完整 scheme 与正常 Release packaging；真机确认惯性点击和空白屏修复；用户确认 Proposed 后实现 P1-A 至 P1-D |
| 1 | **IOS-LOCAL-SIGNING-STABILITY** | 本机 Apple Team / XcodeGen 签名选择持久化 | Full | implemented / 最新 337 iOS tests + 连续 device signing build verified | P1 | 重连 iPhone，从 Xcode 连续 Run 两次，确认 GUI 不再要求选择 Team |
| 2 | **IOS-CHAT-MARKDOWN-VISUAL-POLISH** | 蓝色用户 Query 与 Markdown 阅读体验 | Mid | implemented / 337 iOS tests + Release verified | P1 | 用户真机视觉/VoiceOver 与系统剪贴板验收 |
| 3 | **IOS-PROTOTYPE-APP-PARITY** | iOS 原型与真实 App 一致性 | Full | Current 已同步至柔和蓝 Query 与 Markdown 语义块 | P1 | 继续真机视觉确认；新 UI 先画 Target 再落地 |
| 4 | **IOS-AGENT-FIRST-CHAT** | 以 Agent 结果为中心的手机聊天页 | Full | implemented / Full automated verified（337/337） | P1 | 用户真机视觉/VoiceOver 验收 |
| 5 | **IOS-INTERACTIVE-ARTIFACTS** | 手机端 Personal App / Interactive Artifact | Full | implemented / simulator-verified；待真机 dogfood | P1 | 真机预算规划器与无障碍验收 |
| 6 | **IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH** | iOS 失败恢复、Retry、卡片/HTML 视觉与工具契约收口 | Full | implemented / verified（P0–P1-D complete） | P1 | 真机 dogfood：签名、APNs、后台与实际网络 |
| 7 | **IOS-PERSONAL-APP-LIBRARY** | 跨 Session 的 Personal App 统一入口 | Full | implemented / 323 iOS tests verified；紧凑卡片与 loading skeleton 已补齐；待真机 dogfood | P1 | 用户真机验证首帧、50 个 Artifact、缓存/重启、跨 Session 与 revoke |
| 8 | **IOS-AGENT-FILE-DELIVERY** | Agent 在普通 Chat 中向 App 用户交付生成文件 | Full | implemented；真实 Agent + iOS 真机验收待运行 | P1 | 真机 image/document 下载、预览、分享 |
| 9 | **IOS-TASK-COMPLETION-PUSH** | Task 完成/失败/等待输入后的 APNs 系统通知 | Full | implemented；真实 APNs 到机验收待凭据和真机 | P1 | 配置付费 Apple team/APNs 凭据后验收 |
| 10 | **IOS-AGENT-SESSION-LIVE-FOLLOW** | Agent/Session 导航、智能跟随、`···` 运行指示器与新消息提醒 | Mid / Full split | Phase 1 已实现并验证；Phase 2 待设计 | P1 | Mid 本地交互已交付；跨 Session badge/banner、APNs/后台与对账另走 Full |
| 11 | **TASK-RESUME-ON-RESTART** | kill 后恢复遗留 running task | Full | 单实例 Kill Recovery MVP 已提出；待批准，未实现 | P1 | 启动时统一扫描 root/SubAgent/Workflow；安全 tail 继续，未知副作用转 Interrupted |
| 12 | **CONTEXT-OVERFLOW-TRIPLE-FULL-COMPACT** | context overflow 后最多三次 Full Compact + retry | Full | Core 已实现；74 项聚焦回归与 3471 项 Core+Server reactor 测试通过 | P1 | 联调真实 provider 的 context overflow 错误映射与 UI 提示 |
| 13 | **IOS-ASSISTANT-COMPANION** | iOS V1 core 的发布收口 | Full | V1 core verified；TestFlight/真机发布回归开放 | P2 | 文件交付和 APNs 已拆包；补签名、摄像头、LAN/Tailscale、后台真机门 |
| 14 | **AUTOEVOLVING-MASTER** | V2–V5 总路线 | Full | V1 done；V2–V5 待启动 | P2 | 从 AUTORESEARCH 子包启动 V2 |
| 15 | **AUTOEVOLVE-CLOSE-LOOP** | 阶段 B / G3 / P3 | Full | 部分交付 | P2 | 继续积累干净赢家观察，再决定阶段 B |
| 16 | **AUTORESEARCH-OPTIMIZATION** | arXiv/GitHub 调研 → 人审 → backlog | Full | prd-draft | P3 | ratify D1–D5 与 Q1–Q5 |
| 17 | **ACP-EXTERNAL-AGENT** | cc/codex 外部 coding agent 后续 | Full | 主闭环已交付；小项开放 | P3 | L2 确认门、Codex 工具标签和 AC-3 按需另拆 |

需求包：

- [IOS-CHAT-CONTROL-AGENTS-EXPERIENCE](requirements/active/2026-07-18-IOS-CHAT-CONTROL-AGENTS-EXPERIENCE/index.md)
- [IOS-AGENT-SESSION-LIVE-FOLLOW](requirements/active/2026-07-18-IOS-AGENT-SESSION-LIVE-FOLLOW/index.md)
- [IOS-LOCAL-SIGNING-STABILITY](requirements/active/2026-07-18-IOS-LOCAL-SIGNING-STABILITY/index.md)
- [IOS-CHAT-MARKDOWN-VISUAL-POLISH](requirements/active/2026-07-18-IOS-CHAT-MARKDOWN-VISUAL-POLISH/index.md)
- [IOS-AGENT-FILE-DELIVERY](requirements/active/2026-07-16-IOS-AGENT-FILE-DELIVERY/index.md)
- [IOS-TASK-COMPLETION-PUSH](requirements/active/2026-07-16-IOS-TASK-COMPLETION-PUSH/index.md)
- [IOS-INTERACTIVE-ARTIFACTS](requirements/active/2026-07-16-IOS-INTERACTIVE-ARTIFACTS/index.md)
- [IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH](requirements/active/2026-07-17-IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH/index.md)
- [IOS-PERSONAL-APP-LIBRARY](requirements/active/2026-07-17-IOS-PERSONAL-APP-LIBRARY/index.md)
- [IOS-AGENT-FIRST-CHAT](requirements/active/2026-07-17-IOS-AGENT-FIRST-CHAT/index.md)
- [IOS-PROTOTYPE-APP-PARITY](requirements/active/2026-07-16-IOS-PROTOTYPE-APP-PARITY/index.md)
- [TASK-RESUME-ON-RESTART](requirements/backlog/TASK-RESUME-ON-RESTART/index.md)
- [CONTEXT-OVERFLOW-TRIPLE-FULL-COMPACT](requirements/backlog/CONTEXT-OVERFLOW-TRIPLE-FULL-COMPACT/index.md)
- [IOS-ASSISTANT-COMPANION](requirements/active/2026-07-09-IOS-ASSISTANT-COMPANION/index.md)
- [AUTOEVOLVING-MASTER](requirements/active/2026-05-28-AUTOEVOLVING-MASTER/index.md)
- [AUTOEVOLVE-CLOSE-LOOP](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md)
- [AUTORESEARCH-OPTIMIZATION](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/index.md)
- [ACP-EXTERNAL-AGENT](requirements/active/2026-06-19-ACP-EXTERNAL-AGENT/index.md)

## 当前产品结论

- **Chat / Control / Agents 下一轮**：P0 回到底部已改为 UIKit 只停止惯性、SwiftUI proxy 定位真实 bottom anchor，并用
  Session/generation 隔离 request；26 项 focused XCTest、5 项 Chat XCUITest 与 10 次高风险重复均通过。最终完整
  scheme 在无关 Interactive Artifact WebView runner 异常后被宿主 CoreSimulator 崩溃中断，正常 Release Asset
  Catalog packaging 也被同一宿主 system policy 阻塞；恢复 CoreSimulator host 后补跑完整门禁，真机惯性点击仍待
  dogfood。OpenClaw 官方 iOS 源码与
  Apple HIG 支持“紧凑身份
  Header、活动摘要式 Tool、Control 运行 hub、轻量 Agents roster”的方向；Proposed 四屏获确认后再迁入真实 App。
- **本机签名**：tracked base xcconfig + ignored local xcconfig 已接入 App、Unit Test、UI Test 的 Debug/Release；
  XcodeGen 连续生成确定，连续两次 generic iOS device signing build已通过；后续最新 Release build 与完整 scheme
  337/337 也通过。
  修复后真实 iPhone 已断开，Xcode GUI 的连续 no-prompt Run 留待设备重连确认。
- **Query / Markdown 视觉**：用户已选择柔和蓝 Query 与保留安全边界的语义块升级；生产 SwiftUI、代码精确复制、
  Light/Dark/XXXL 和 Current 原型均已同步，完整 scheme 337/337 与 Release simulator build 通过。
- **Interactive Artifact**：方向可行；采用消息摘要 + Personal App 卡片 + 离线全屏 Viewer，不恢复旧的 Markdown 内任意 HTML 执行方案。
- **Personal App Library**：跨 Session 聚合、搜索筛选、收藏、离线缓存和来源定位已完成；卡片摘要限制为 96 字符，
  冷加载改为稳定 header + 同构 skeleton，iOS 323/323 自动化通过。下一步为用户真机 dogfood。
- **iOS 首帧**：空 `UILaunchScreen` 已替换为 SkillForge 品牌启动资产，SwiftUI 恢复阶段不再显示纯白/孤立 spinner；
  设备 token 与 endpoint 校验保持原边界，仍需用户真机冷启动确认系统 snapshot 缓存后的实际过渡。
- **Agent-first Chat**：真实 App 已将用户 Query 收为柔和蓝紧凑气泡、Agent 正文扩到接近完整阅读区，并把 Tool、文件与
  Personal App 放入回答流；Current 原型已同步，生产中不存在的回复操作已移除。完整 scheme 337/337 与 Release
  simulator build 已通过，待用户真机视觉/VoiceOver 验收。
- **Agent 文件交付**：工具白名单缺口已修复；真实 normal-chat image/document 与 iOS 真机仍待验收。
- **Task 完成推送**：服务端与 iOS 链路已实现；免费 Personal Team 的 Debug 构建不支持 APNs，真机到达待付费团队与凭据。
- **Compact 原故障**：已解决并归档；极端 no-safe-boundary、watchdog、旧 orphan 清理只留 watchlist。

## Backlog

| ID | 触发条件 |
| --- | --- |
| **WF-CONCURRENT-PIPELINE** | AUTOEVOLVING V2(d) 启动或串行 pipeline 成为瓶颈 |
| **OUTCOMES-RUBRIC-FOUNDATION** | 用户决定把 rubric/grader 升 active |
| **COMPACT-V2-STORAGE** | recovery 回取或截断 cache 抖动出现真实需求 |
| **CHANNEL-RICH-MESSAGE** | 需要微信原生视频或跨渠道卡片 |
| **CHANNEL-PUSH-SERVICE** | 出现第二个通用主动推送客户 |
| **WEBSEARCH-SEARXNG-BACKEND** | 搜索成本、隐私或内网诉求触发 |
| **EVAL-DYNAMIC-USER-SIM** | 需要把动态多轮模拟纳入 A/B gate |
| **SEC-1** | 多用户/公网或生产 channel 密钥保护进入排期 |

完整 backlog 见 `docs/requirements/backlog/`；明确暂缓见 `docs/requirements/deferred/`。

## 本次归档 / 暂缓清理

| ID | 处理 | 依据 |
| --- | --- | --- |
| COMPACT-IDEMPOTENCY-BOUNDARY-FIX | 归档 done | 负 gap、窗口分块、range model、frontier 均已实现并有聚焦测试 |
| EVOLVE-JUDGE-GROUNDING | 归档 done | Phase 1 commit `5be19db9` |
| EVOLVE-CANDIDATE-GROUNDING | 归档 done | Phase 2 commit `775fe4df`，FR-C7 后续已收口 |
| WECHAT-CHANNEL | 归档 done | Slice 1–3 已交付；真手机扫码仅发布验收门 |
| PERSONAL-WORKSPACE-BROWSER | 归档 done | V1 2026-07-14 已验证 |
| ANNOTATOR-BEHAVIOR-SIGNALS | 归档 done | commit `f273bae7` |
| OPT-REPORT-V1 | 归档 done | commits `e3fde131` / `f50d91b8` |
| DESKTOP-MACOS-PACKAGE | 移 deferred | 基础实现存在，但 `.dmg`、签名、公证和安装验收未收口 |
