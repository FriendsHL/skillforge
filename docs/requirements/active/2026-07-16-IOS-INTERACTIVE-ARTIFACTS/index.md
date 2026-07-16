# IOS-INTERACTIVE-ARTIFACTS — 手机端 Personal App / Interactive Artifact

> 状态：approved / full-ready；用户已确认 V1 默认边界，下一步进入 Full pipeline
> 优先级：候选 P1
> 范围：当前只包含调研、产品边界和交互原型，不包含生产代码

## 用户意图

电脑端 Task 结果通常以 Markdown 或文件交付；个人手机端更适合把部分结果变成可触摸、可筛选、
可继续操作的小应用。用户希望先用个人手机验证这种体验，再决定是否进入 Full pipeline。

## 结论

方向可行，而且与 Claude Artifacts、Gemini Canvas、ChatGPT Apps SDK、MCP Apps 的共同演进方向一致。
但产品对象应是受管的 `InteractiveArtifact`，不是把裸 `.html` 文件当普通附件，也不应全面替代 Markdown。

推荐首版采用：

- 消息中保留 3–6 行 Markdown 摘要。
- 同一 assistant message 附带一个 Personal App 卡片。
- 点击后在 App 内全屏打开离线、自包含、默认无网络的 HTML/CSS/JS bundle。
- 首版只能操作 Artifact 自己的数据，不调用 SkillForge tool，不注入 device token。
- 用户操作状态保存在 artifact-scoped storage；返回聊天后可把结构化摘要交给 Agent 继续处理。

## 文档

1. [research-report.md](research-report.md) — 完整调研报告与可操作性结论
2. [prd-draft.md](prd-draft.md) — 待确认产品范围
3. [tech-options.md](tech-options.md) — 技术路径、安全模型和 Full pipeline 切分
4. [iOS 交互原型](../../../prototypes/ios-assistant-companion/index.html) — 新增 Personal App 卡片与全屏态

## 已批准边界（2026-07-16）

1. V1 离线、自包含、无 Tool Bridge。
2. 每条 assistant message 最多一个 Personal App；暂不做独立 App Library。
3. 用户原生确认后，可把 schema-valid 结构化状态作为 user message 发回 Agent。
4. V1 只支持受控、自包含资源，不支持任意 npm/远程依赖。
