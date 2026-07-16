# MRD — Agent 向 App 用户交付文件

## 用户请求

> SkillForge APP agent 不能给用户发送文件。

## 用户问题

Agent 即使生成了文件，App 用户也无法像接收普通消息一样收到产物，导致移动端不能完成“交任务 →
等结果 → 拿文件”的闭环。

## 目标体验

1. 用户在 App 中要求 Agent 生成文件。
2. Agent 完成后，文件作为最终 assistant 消息的一部分出现。
3. 用户可以下载、预览、分享；失败时可以重试。
4. 切换会话、断线重连或 REST catch-up 后，文件仍只出现一次。

## 已知范围

- 首版支持已有基础设施声明支持的图片、PDF、Word、Excel、CSV。
- 普通 Chat 是本包主路径；微信/飞书的文件发送协议不在本包范围。
- 不开放任意本机路径；Agent 只能发布本次运行的受管 artifact workspace 内文件。

## 待取证问题

- Agent 是否实际看见并调用 `PublishChatArtifact`。
- 未调用时是 prompt/工具选择问题，还是模型能力/工具 registry 问题。
- 调用成功后，assistant attachment ref 是否进入最终持久化消息和移动端 DTO。
- iOS 是未渲染、下载鉴权失败，还是用户所用版本尚未包含该实现。
