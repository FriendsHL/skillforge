# IOS-ASSISTANT-COMPANION MRD

## 用户诉求

用户希望参考 OpenClaw 的 iOS 端产品，为 SkillForge 做一个 iOS 端。方向经过澄清后确定为：

- 更倾向 OpenClaw 式个人助理入口，而不是 dashboard mobile。
- 配对连接方式希望参考 OpenClaw：扫码连接自己的后端。
- endpoint 策略先按 MVP 保存一个可达 endpoint，但协议上预留 `endpoints[]`，后续像 OpenClaw 一样自动探测/切换。
- 首屏更倾向聊天优先。
- iOS 技术栈倾向 SwiftUI 原生。
- 语音输入 V1 先依赖 iOS 系统键盘听写，不做自建语音链路。

## OpenClaw 参考点

OpenClaw iOS 端不是简单手机网页壳，而是连接 Gateway 的 companion client。它的关键启发是：

- Gateway/Server 是控制面。
- iOS App 通过 QR/setup code 配对。
- App 持有连接信息并连接用户自己的 Gateway。
- 移动端适合承担聊天、语音、确认、push、分享和设备能力入口。

SkillForge 采纳其中的 pairing 和 companion 思路，但 V1 不做完整设备节点。

## 目标用户

V1 先服务 SkillForge owner 自用场景：

- 本地或私有部署 SkillForge。
- 想在手机上随时发起任务。
- 想在外出时处理长任务确认、ask_user、完成通知。
- 想把手机里的文件/图片投喂给 agent。

后端模型要预留多用户/多设备，不把 MVP 写死为单设备临时代码。

## 痛点

- 当前 SkillForge 主要依赖 dashboard 和外部 channel，手机上没有 owner 自己可控的原生入口。
- 长任务需要确认或回答时，用户必须回到浏览器。
- 外出时无法方便查看任务状态或收到完成通知。
- 从手机分享文件、图片、网页到 SkillForge 不顺手。

## 成功标准

- 用户能扫码配对 iPhone。
- 配对后直接进入默认助理聊天。
- 手机上能完成基本聊天、确认和文件上传。
- 长任务完成/失败/需要输入时能推送提醒。
- 后续升级到真语音、Share Extension、设备节点时不需要重做 pairing 协议。

## 约束

- 不引入 SkillForge 官方 relay，先连接用户自己的 server。
- 不要求 V1 支持复杂公网穿透；Tailscale/公网 HTTPS/局域网由用户部署决定。
- 不把长期 token 放进 QR。
- 不开放高敏手机权限。
