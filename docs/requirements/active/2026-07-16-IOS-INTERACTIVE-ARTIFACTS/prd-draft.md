# PRD Draft — iOS Personal App / Interactive Artifact

> 状态：approved / full-ready；2026-07-16 用户已确认默认范围

## 用户故事

作为 SkillForge 的个人手机用户，我希望 Agent 不只给我长 Markdown，而能把适合操作的 Task 结果
变成一个临时小应用，让我用点击、选择、滑动和勾选完成下一步。

## V1 功能范围

1. assistant message 可同时包含 Markdown 摘要、普通文件和一个 Interactive Artifact。
2. Chat 中展示 Personal App 卡片，不在消息列表直接运行页面。
3. 点击卡片进入 App 内全屏 Viewer；返回后仍回到原消息位置。
4. 支持离线、自包含 HTML/CSS/JS 与本地数据文件。
5. 支持 artifact-scoped 状态保存、重置和系统分享。
6. 用户可主动把当前状态摘要发给 Agent；页面不能静默发送消息。
7. App 必须显示来源 Agent、生成时间、离线状态和权限状态。
8. Markdown fallback 在所有不支持 Interactive Artifact 的渠道可见。

## V1 非目标

- 任意外网访问、远程 CDN、动态 npm 依赖。
- Tool Bridge、camera、microphone、location、clipboard 或文件系统权限。
- 在 Markdown 中自动执行 fenced HTML。
- 让 HTML 读取 SkillForge token、Cookie或完整 transcript。
- 微信/飞书内运行页面；它们只收到摘要和受控链接/附件降级。
- 多人协同编辑、公开托管和 Artifact marketplace。

## 验收场景

1. Agent 生成预算规划器；用户调整两个分类，关闭并重开后状态保留。
2. 页面无网络仍可完整运行；尝试 fetch 外部域被阻止且有可诊断记录。
3. 点击“发给 Agent”先出现原生预览/确认，批准后只发送 schema-valid state snapshot。
4. App 重启、REST catch-up 和 WebSocket 重连后卡片不丢失、不重复。
5. device revoke、跨 user、跨 session 获取 bundle 均被拒绝。
6. 不支持该类型的客户端仍显示 fallback Markdown 摘要。
7. Dynamic Type、VoiceOver、深色模式和窄屏下核心操作可完成。

## 已确认决策

- V1 不做独立 Personal App Library / 收藏页。
- 每条 assistant message 最多一个 Interactive Artifact。
- state snapshot 经原生确认后进入普通 user message。
- Dashboard V1 只做安全预览/降级，iOS 是主验证面。
