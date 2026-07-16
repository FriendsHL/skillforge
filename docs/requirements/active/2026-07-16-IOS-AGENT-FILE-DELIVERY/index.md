# IOS-AGENT-FILE-DELIVERY — Agent 向 App 用户交付文件

> 状态：implemented（工具白名单缺口已修复；真实 Agent + iOS 真机文件验收待运行）
> 模式：Full（Agent Loop 消息形状、附件持久化、移动端 API 和 iOS 文件交互）
> 优先级：P1

## 摘要

用户反馈 SkillForge App 中 Agent 不能把生成的文件发送给用户。仓库已经具备
`PublishChatArtifact`、受管 artifact workspace、assistant attachment refs、移动端鉴权下载以及
iOS 预览/分享基础设施，但原 V1 的两个端到端验收项从未通过，因此不能把该能力视为已交付。

本包目标是让普通非 channel Chat 中的 Agent 能可靠交付生成文件，用户在 App 中能看到、下载、
预览和分享，并保证 Dashboard 与 iOS 各显示一次。

## 2026-07-16 根因取证

- 最近一个普通 App 会话使用 `Research Agent`；其 `tool_ids` 不包含 `PublishChatArtifact`。
- 当前数据库只有 8 条 `user_upload` 附件，没有任何 `agent_generated` 附件，证明失败发生在发布之前。
- `Main Assistant` 已拥有 `PublishChatArtifact`，但 Design、Code、Research、Session Analyzer 等普通
  user Agent 均未拥有，导致同一个 App 中能力随 Agent 选择而变化。
- artifact workspace、发布工具注册、Agent Loop artifact sidecar、移动端下载以及 iOS 展示链路已有
  单元测试和实现；本轮不需要先修改消息协议。

## 阅读顺序

1. [mrd.md](mrd.md)
2. [prd.md](prd.md)
3. [tech-design.md](tech-design.md)
4. 原 iOS V1 的 [Outbound Artifact Foundation](../2026-07-09-IOS-ASSISTANT-COMPANION/index.md#outbound-artifact-foundation-已批准边界)

## 实施结果

- V172 已为 active `agent_type=user` 的显式非空工具白名单幂等追加 `PublishChatArtifact`。
- 当前本地数据库已验证 Design、Code、Main、Research、Session Analyzer 均可发布，system Agent 未授权。
- artifact 发布、Agent Loop sidecar、移动端鉴权下载及迁移定向测试 15/15 通过。
- 服务端完整测试 3325 个，0 失败，175 跳过。
- 待 App 真机分别让普通 Agent 生成图片和文档，完成下载、预览和分享最终验收。
