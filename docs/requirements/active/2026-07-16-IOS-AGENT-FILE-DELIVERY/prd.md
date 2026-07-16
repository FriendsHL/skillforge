# PRD — Agent 向 App 用户交付文件

> 状态：implemented；真实 Agent + iOS 真机文件验收待运行。

## 目标

让 SkillForge App 用户可以可靠接收 Agent 在当前任务中生成的文件。

## 功能要求

1. Agent 能发布图片、PDF、Word、Excel、CSV。
2. 文件必须附着在一个 assistant 消息上，可同时带简短说明。
3. App 展示文件名、类型、大小/页数等已有元数据以及下载状态。
4. 图片支持预览；文档支持系统 Quick Look；所有支持类型均可分享。
5. 下载必须使用当前已配对设备身份，校验 user、session 和 attachment 所有权。
6. WebSocket 实时消息、REST 历史和重连合并不得重复展示同一附件。
7. 发布失败时 Agent/用户必须得到明确失败信息，不能只说“文件已生成”却没有附件。
8. 所有 active `agent_type=user` Agent 都具备发布能力；是否实际生成文件仍受其现有 Write/Bash 等
   工具能力约束，system/eval Agent 不自动获得该工具。

## 非目标

- ZIP、可执行文件和任意二进制。
- 从仓库、home 或 `/tmp` 任意路径直接外发。
- 微信、飞书等 channel 的富文件协议升级。

## 验收标准

1. 在真实普通 Chat 中，Agent 分别生成并发布一张图片和一个文档。
2. Dashboard 与 iOS 对每个附件各显示一次，文件名和类型一致。
3. iOS 真机完成下载、预览和系统分享。
4. App 重启、断网重连和 REST catch-up 后附件不丢失、不重复。
5. 撤销设备、跨用户和跨 session 下载均被拒绝。
6. Agent 未调用发布工具或发布失败时，最终消息不得虚假声称附件已交付。
