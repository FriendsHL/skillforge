# WECHAT-CHANNEL — 增加微信 channel

> 创建：2026-06-18
> 状态：prd-draft（**待用户拍路线**：路线 A 企业微信自建 / 路线 B openclaw-wechat bridge）
> 模式：Full（新 channel 平台适配器 + 外部输入 + 验签/AES 解密 + 密钥；安全敏感）
> 来源：用户「当前 channel 只支持飞书，要加微信」+ 三轮调研（SkillForge channel 架构 / 微信各形态 API / openclaw 怎么做）。

## 摘要

给 SkillForge 加微信收发能力(现仅飞书)。SkillForge channel 是适配器架构(`channel/platform/` 下已有 feishu/telegram/mock,SPI 自动注册),加平台 = 加一个 adapter 包 + 2 处 switch,**无需 migration**。难点不在工程,在**选哪条微信路线**——取决于"bot 的用户是谁"。

## 两条路线(调研结论)

### 路线 A — 企业微信自建应用 ✅ 推荐(默认)
- **用户**:企业成员(+ 经"微信客服"可触达外部/公网微信用户,有 48h/5 条窗口)。
- **机制**:回调收(Token + EncodingAESKey + AES-256-CBC 解密 + msg_signature 验签)+ `access_token` 主动发(`message/send`,**无回复窗限制**)。
- **契合度**:几乎是飞书自建应用的镜像 → **完美落进 SkillForge 现有 Java adapter 模型**(照抄 telegram/feishu 三件套)。
- **代价**:**没有扫码 UX**(管理员配 corpId/agentId/secret/Token/AESKey + 可信 IP 白名单);需公网 HTTPS 回调;不要付费认证即可内部用。

### 路线 B — openclaw-wechat bridge ⚠️ 仅当需要"个人/公网微信 + 扫码"
- **机制**:单独跑 openclaw(或其微信插件 `@tencent-weixin/openclaw-weixin`,腾讯官方),`channels login` 扫码绑微信,再把消息**转发**到 SkillForge。
- **能拿到**:扫码 UX + 触达个人/公网微信用户。
- **代价**:① 那个插件**绑死 openclaw 的 JS 插件契约 + Node 运行时,SkillForge(Java SPI)不能直接用**,只能"借壳 bridge";② 多一个进程/依赖;③ 本质依赖腾讯给 openclaw 单独开的口子,非通用协议,可控性差。
- **注**:个人微信无官方 API;"微信ClawBot"是腾讯专给 openclaw 生态开的,不是 SkillForge 能直接对接的协议。

## 推荐

**路线 A(企业微信自建应用)**——官方、稳、契合现有架构、无借壳。除非你明确要"用个人/公网微信 + 扫码绑定"的体验,才走 B。多数"团队内部 AI bot"场景 A 足够;要触达外部公网用户,A 还能叠"微信客服"(窗口受限)而不必上 B。

## 待用户拍(gating 决策)

**bot 的用户是谁?** → 决定路线:
- 企业成员 / 内部团队 → **路线 A**
- 必须是你的个人微信 / 公网微信用户 + 扫码体验 → 路线 B

拍完我再写该路线的 prd/tech-design(路线特定,现在写两条会浪费)。

## 阅读顺序

1. 本 index(方案 + 决策点)
2. [mrd.md](mrd.md) — 用户诉求 + 三轮调研出处（含 SkillForge 扩展点 / 微信 API / openclaw 机制）
3. prd.md / tech-design.md — **待路线拍定后补**

## 关联

- SkillForge channel 扩展点:`channel/spi/ChannelAdapter`(5 必需方法,自动注册)+ `ChannelConfigController` 2 处 per-platform switch + `t_channel_config`(无 migration)。
- 顺带触发 [SEC-1](../../backlog/SEC-1-channel-config-encryption/index.md)(channel 密钥目前明文存)——接生产微信是其重评触发点。
