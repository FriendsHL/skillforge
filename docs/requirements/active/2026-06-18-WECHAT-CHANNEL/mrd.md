# MRD — WECHAT-CHANNEL

## 用户诉求

> 「当前 channel 只支持飞书，现在要增加微信。你去搜搜应该如何配置。」
> 「我记得之前微信可以绑定 openclaw 的，有个配置扫二维码就行了。你查查 / 你看下 openclaw 是怎么做的。」
>（2026-06-18）

用户要给 SkillForge 加微信 channel，并记得 openclaw 有"扫码绑定微信"的体验，想参考。

## 三轮调研出处（事实依据）

### 1. SkillForge channel 扩展点（读代码）
- 适配器 SPI `channel/spi/ChannelAdapter`（5 必需方法：platformId/displayName/verifyWebhook/parseIncoming+handleVerificationChallenge/deliver），Spring `List<ChannelAdapter>` **自动注册**（platformId→adapter，无 enum）。已有 feishu(富,含 WS+card)/ telegram(最简 3 文件)/ mock。
- 唯二手动接线：`ChannelConfigController` 的连接测试 switch + resolveWebhookSecret switch。
- 配置进 `t_channel_config`（platform=VARCHAR 非 enum，credentials_json/webhook_secret/config_json），**加平台无需 migration**。
- 密钥**明文存**（SEC-1 加密 deferred）→ 接生产微信是 SEC-1 重评触发点。
- 飞书入站两路：webhook（SHA-256 验签 + AES）/ websocket（protobuf）；出站 tenant_access_token + 发卡片。**SkillForge 的 deliver 是异步主动发模型**——契合微信主动发，不契合公众号被动回复。

### 2. 微信各形态 API（web 查官方文档）
- **企业微信自建应用** ✅：回调 Token+EncodingAESKey+AES-256-CBC，msg_signature=SHA1(sort(token,timestamp,nonce,**密文**))；主动发 gettoken(corpid+corpsecret)+message/send，**无回复窗**；需可信 IP 白名单(errcode 60020)。是飞书模型的镜像。
- **公众号** ⚠️：被动回复 5s 窗 / 客服消息 48h 窗 + 需认证服务号(付费)才能自由主动发 → 跟 SkillForge 异步模型冲突。
- **微信客服** ⚠️：能触达公网用户但 48h/5 条窗。
- **个人微信** ❌：无官方 API，逆向 hack 违 ToS。
- 来源：developer.work.weixin.qq.com / developers.weixin.qq.com 官方文档。

### 3. openclaw 怎么做（读 openclaw 源码 + web）
- openclaw 核心定义 `ChannelDefinition` 插件契约（config/outbound/gateway 必需 + 15+ 可选 adapter，含 `pairing?: ChannelPairingAdapter` 扫码配对），核心**完全不感知具体 IM**。
- 私有协议（微信/QQ/大象）**走外部 npm 包**，不在核心仓（已在 openclaw checkout 确认核心无微信/pairing 实现）。
- 微信 = **腾讯官方** `@tencent-weixin/openclaw-weixin` 插件，`openclaw channels login --channel openclaw-weixin` 出二维码 → 微信扫(设置-插件-ClawBot)→ token 存本地；微信协议/运行时**全在该外部插件**。
- **关键**：这是腾讯专给 openclaw 生态开的口子，绑死 openclaw JS 插件契约 + Node 运行时，**SkillForge(Java)不能直接用**，只能"借壳 bridge"。

## 核心约束 / 决策

- 工程不难（适配器架构现成）；**难在选路线**（A 企业微信自建 / B openclaw bridge），由"bot 用户是谁"决定 → 列为 gating 决策（见 index）。
- 不碰个人微信逆向方案。
- 路线 A 契合现有架构；路线 B 需额外 Node 进程 + 借壳。

## 未解决（待用户拍）

- **路线选择**（gating，见 index "待用户拍"）。
- 若 A：是否同期叠"微信客服"以触达外部公网用户（窗口受限）。
- 是否同期做 SEC-1（密钥加密）——接生产微信前应做。
