# SkillForge ToDo

> 更新于：2026-07-16（新增 iOS Interactive Artifact 调研与原型；文件交付/APNs 已实现待真机验收）
> 规则：这里只放当前执行状态；范围与方案见需求包，交付事实见 [delivery-index.md](delivery-index.md)。

## 当前队列

| 顺序 | ID | 标题 | 模式 | 状态 | 优先级 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | **IOS-PROTOTYPE-APP-PARITY** | iOS 原型与真实 App 一致性 | Full | Phase 1 implemented；待视觉确认 | P1 | 确认 Current / Proposed 后启动 Interactive Artifact |
| 1 | **IOS-INTERACTIVE-ARTIFACTS** | 手机端 Personal App / Interactive Artifact | Full | approved / full-ready | P1 | parity Phase 1 后启动 Full pipeline |
| 2 | **IOS-AGENT-FILE-DELIVERY** | Agent 在普通 Chat 中向 App 用户交付生成文件 | Full | implemented；真实 Agent + iOS 真机验收待运行 | P1 | 真机 image/document 下载、预览、分享 |
| 3 | **IOS-TASK-COMPLETION-PUSH** | Task 完成/失败/等待输入后的 APNs 系统通知 | Full | implemented；真实 APNs 到机验收待凭据和真机 | P1 | 配置付费 Apple team/APNs 凭据后验收 |
| 4 | **TASK-RESUME-ON-RESTART** | 重启后恢复根 agent-loop，再扩 workflow/ACP | Full | backlog；Phase 1 优先级已确认，设计未定稿 | P1 | 解决恢复语义与重复副作用 hard gate |
| 5 | **IOS-ASSISTANT-COMPANION** | iOS V1 core 的发布收口 | Full | V1 core verified；TestFlight/真机发布回归开放 | P2 | 文件交付和 APNs 已拆包；补签名、摄像头、LAN/Tailscale、后台真机门 |
| 6 | **AUTOEVOLVING-MASTER** | V2–V5 总路线 | Full | V1 done；V2–V5 待启动 | P2 | 从 AUTORESEARCH 子包启动 V2 |
| 7 | **AUTOEVOLVE-CLOSE-LOOP** | 阶段 B / G3 / P3 | Full | 部分交付 | P2 | 继续积累干净赢家观察，再决定阶段 B |
| 8 | **AUTORESEARCH-OPTIMIZATION** | arXiv/GitHub 调研 → 人审 → backlog | Full | prd-draft | P3 | ratify D1–D5 与 Q1–Q5 |
| 9 | **ACP-EXTERNAL-AGENT** | cc/codex 外部 coding agent 后续 | Full | 主闭环已交付；小项开放 | P3 | L2 确认门、Codex 工具标签和 AC-3 按需另拆 |

需求包：

- [IOS-AGENT-FILE-DELIVERY](requirements/active/2026-07-16-IOS-AGENT-FILE-DELIVERY/index.md)
- [IOS-TASK-COMPLETION-PUSH](requirements/active/2026-07-16-IOS-TASK-COMPLETION-PUSH/index.md)
- [IOS-INTERACTIVE-ARTIFACTS](requirements/active/2026-07-16-IOS-INTERACTIVE-ARTIFACTS/index.md)
- [IOS-PROTOTYPE-APP-PARITY](requirements/active/2026-07-16-IOS-PROTOTYPE-APP-PARITY/index.md)
- [TASK-RESUME-ON-RESTART](requirements/backlog/TASK-RESUME-ON-RESTART/index.md)
- [IOS-ASSISTANT-COMPANION](requirements/active/2026-07-09-IOS-ASSISTANT-COMPANION/index.md)
- [AUTOEVOLVING-MASTER](requirements/active/2026-05-28-AUTOEVOLVING-MASTER/index.md)
- [AUTOEVOLVE-CLOSE-LOOP](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md)
- [AUTORESEARCH-OPTIMIZATION](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/index.md)
- [ACP-EXTERNAL-AGENT](requirements/active/2026-06-19-ACP-EXTERNAL-AGENT/index.md)

## 当前产品结论

- **Interactive Artifact**：方向可行；采用消息摘要 + Personal App 卡片 + 离线全屏 Viewer，不恢复旧的 Markdown 内任意 HTML 执行方案。
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
